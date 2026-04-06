package com.simpleclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleclaw.agent.skills.SkillsLoader;
import com.simpleclaw.agent.tools.fs.*;
import com.simpleclaw.agent.tools.system.*;
import com.simpleclaw.agent.tools.communication.*;
import com.simpleclaw.bus.InboundMessage;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.model.ExecToolConfig;
import com.simpleclaw.config.model.MCPServerConfig;
import com.simpleclaw.config.model.PromptsConfig;
import com.simpleclaw.cron.CronService;
import com.simpleclaw.cron.WheelCronService;
import com.simpleclaw.memory.MemoryManager;

import com.simpleclaw.providers.LLMProvider;
import com.simpleclaw.providers.ToolCallRequest;
import com.simpleclaw.agent.model.RunResult;
import com.simpleclaw.session.SessionManager;
import com.simpleclaw.session.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Agent 主循环：消费 inbound、建会话/上下文、调 LLM、执行 tool_call、写回复/会话；支持 processDirect（CLI/Cron/Heartbeat）。
 * 
 * 核心职责：
 * 1. 消费入站消息并分发处理
 * 2. 管理会话生命周期（获取/创建/保存）
 * 3. 执行 Agent 循环（多轮对话+工具调用）
 * 4. 持久化会话和消息
 * 5. 调度后台记忆整合
 * 
 * 线程安全：
 * - 使用 ConcurrentHashMap 管理活跃任务
 * - 每个会话有独立的锁保证串行处理
 */
@Slf4j
public class AgentLoop {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    /** 工具结果最大字符数，超过则截断 */
    private static final int TOOL_RESULT_MAX_CHARS = 16000;

    private final MessageBus bus;
    private final LLMProvider provider;
    private final Path workspace;
    private final String model;
    private final int maxIterations;
    private final double temperature;
    private final int maxTokens;
    private final int memoryWindow;
    private final ExecToolConfig execConfig;
    private final boolean restrictToWorkspace;
    private final SessionManager sessionManager;
    private final ToolRegistry toolRegistry;
    private final ContextBuilder contextBuilder;
    private final SkillsLoader skillsLoader;
    private final int maxCompletionTokens = 1024;  // 默认最大完成 tokens

    /** 【记忆管理器】统一管理层：会话压缩 + Memory Flush + 记忆检索 */
    private MemoryManager memoryManager;

    /** 运行状态标志 */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** 定时任务服务 */
    private CronService cronService;

    /** MCP 服务器配置 */
    private Map<String, MCPServerConfig> mcpServers;
    
    /** Agent 配置 */
    private AgentConfig agentConfig;
    
    /** 活跃任务映射：sessionKey -> 任务列表 */
    private final ConcurrentHashMap<String, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();
    
    /** 会话锁映射：sessionKey -> 锁对象，保证同一会话串行处理 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    
    /** 后台任务执行器 */
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();

    /**
     * 【简化构造函数】使用 AgentConfig 封装配置
     * 
     * @param bus 消息总线
     * @param provider LLM 提供商
     * @param agentConfig Agent 配置
     * @param sessionManager 会话管理器
     * @param cronService 定时任务服务（可为 null）
     */
    public AgentLoop(MessageBus bus,
                    LLMProvider provider,
                    AgentConfig agentConfig,
                    SessionManager sessionManager,
                    CronService cronService) {
        this.bus = bus;
        this.provider = provider;
        this.agentConfig = agentConfig;
        this.workspace = agentConfig.getWorkspacePath() != null 
            ? java.nio.file.Paths.get(agentConfig.getWorkspacePath()) 
            : java.nio.file.Paths.get(".");
        this.model = agentConfig.getModel() != null && !agentConfig.getModel().isEmpty() 
            ? agentConfig.getModel() 
            : provider.getDefaultModel();
        this.maxIterations = agentConfig.getMaxToolIterations() > 0 
            ? agentConfig.getMaxToolIterations() 
            : 10;
        this.temperature = agentConfig.getTemperature() >= 0 
            ? agentConfig.getTemperature() 
            : 0.7;
        this.maxTokens = agentConfig.getMaxTokens() > 0 
            ? agentConfig.getMaxTokens() 
            : 4096;
        this.memoryWindow = agentConfig.getMemoryWindow() > 0 
            ? agentConfig.getMemoryWindow() 
            : 20;
        this.execConfig = agentConfig.getExecConfig();
        this.restrictToWorkspace = agentConfig.isRestrictToWorkspace();
        this.sessionManager = sessionManager;
        this.cronService = cronService;
        this.mcpServers = agentConfig.getMcpServers() != null
            ? agentConfig.getMcpServers()
            : Collections.emptyMap();
        // 【初始化 SkillsLoader】使用新的多源扫描技能加载器
        this.skillsLoader = new SkillsLoader(
                this.workspace,
                null, // dataDir 可从 config 获取
                null, // builtinSkillsDir
                agentConfig.getSkillsConfig()
        );
        // 【初始化 ContextBuilder】使用新的构造函数
        this.contextBuilder = new ContextBuilder(
                this.workspace,
                null, // dataDir
                null, // builtinSkillsDir
                agentConfig.getSkillsConfig()
        );
        this.toolRegistry = new ToolRegistry();
        // 【初始化记忆管理器】统一管理层：会话压缩 + Memory Flush + 记忆检索
        try {
            this.memoryManager = new MemoryManager(
                    workspace,
                    agentConfig,
                    provider,
                    contextBuilder,
                    sessionManager,
                    toolRegistry::getDefinitions
            );

            // 【后台索引记忆目录】
            if (this.memoryManager.isEnabled()) {
                new Thread(() -> {
                    memoryManager.indexMemoryDirectory();
                }, "MemoryIndexThread").start();
            }
        } catch (Exception e) {
            log.error("[AgentLoop] 长期记忆管理器初始化失败: {}", e.getMessage());
            this.memoryManager = null;
        }

        registerDefaultTools();
    }

    /**
     * 【注册默认工具】
     * 包含文件操作、系统命令、消息发送、定时任务、时间获取及记忆检索工具。
     */
    private void registerDefaultTools() {
        toolRegistry.register(new ReadFileTool(workspace, restrictToWorkspace));
        toolRegistry.register(new WriteFileTool(workspace, restrictToWorkspace));
        toolRegistry.register(new ListDirTool(workspace, restrictToWorkspace));
        toolRegistry.register(new ExecTool(execConfig));
        toolRegistry.register(new MessageTool(bus));
        if (cronService instanceof WheelCronService) {
            toolRegistry.register(new CronTool((WheelCronService) cronService));
        }
        toolRegistry.register(new TimeTool());

        // 【注册记忆检索工具】让 LLM 主动搜索和读取记忆
        if (memoryManager != null && memoryManager.isEnabled()) {
            toolRegistry.register(new com.simpleclaw.agent.tools.memory.MemorySearchTool(memoryManager));
            toolRegistry.register(new com.simpleclaw.agent.tools.memory.MemoryGetTool(workspace));
//            log.info("[AgentLoop] 已注册记忆检索工具: memory_search, memory_get");
        }
    }

    // ========================================================================
    // 2. 核心运行循环 (Core Loop)
    // ========================================================================

    /** 
     * 【主循环】持续从总线消费入站消息并处理。
     */
    public void run() {
        while (running.get()) {
            try {
                InboundMessage msg = bus.consumeInbound();
                OutboundMessage response = processMessage(msg);
                if (response != null) {
                    bus.publishOutbound(response);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ========================================================================
    // 3. 外部处理接口 (Public Processing Interface)
    // ========================================================================

    /**
     * 【直接处理消息】同步调用，用于 CLI、Cron、Heartbeat 等非总线场景。
     *
     * @param content 消息内容
     * @param channel 渠道标识（如 "cli"、"weixin"）
     * @param chatId 聊天ID
     * @param streamConsumer 流式回调（可为 null）
     * @return Agent 回复文本
     */
    public String processDirect(String content, String channel, String chatId, Consumer<String> streamConsumer) {
        InboundMessage msg = new InboundMessage(channel, "user", chatId, content);
        OutboundMessage out = processMessage(msg, streamConsumer);
        return out != null ? out.getContent() : "";
    }

    /**
     * 【直接处理消息】简化版，无流式回调。
     */
    public String processDirect(String content, String channel, String chatId) {
        return processDirect(content, channel, chatId, null);
    }

    /**
     * 【处理单条入站消息】支持流式输出。
     * 线程安全：使用会话锁保证同一会话串行处理。
     */
    public OutboundMessage processMessage(InboundMessage msg, Consumer<String> streamConsumer) {
        String sessionKey = msg.getChannel() + ":" + msg.getChatId();
        String content = msg.getContent() != null ? msg.getContent() : "";

        if ("system".equals(msg.getChannel())) {
            return handleSystemMessage(msg);
        }

        Object lock = sessionLocks.computeIfAbsent(sessionKey, k -> new Object());
        synchronized (lock) {
            Session session = sessionManager.getOrCreate(sessionKey);
            
            // 【获取 JSONL Store】
            com.simpleclaw.session.JsonlSessionStore jsonlStore = sessionManager.getJsonlStore(sessionKey);

            // 1. 处理特殊命令
            if (handleSpecialCommands(session, content.trim(), msg)) {
                return null; // 特殊命令已处理，无需继续
            }

            // 2. 【检查并执行会话压缩】MemoryManager 统一管理（内部已记录上下文窗口使用情况）
            memoryManager.checkAndCompact(session, jsonlStore, content);

            // 3. 准备上下文（使用 ContextBuilder 统一构建）
            Map<String, Object> requestContext = buildRequestContext(msg);
            List<String> skillNames = skillsLoader.getAlwaysSkills();
            List<Map<String, Object>> initialMessages = contextBuilder.buildChatContext(
                    jsonlStore, content, skillNames);

            // 5. 运行引擎
            RunResult result = runAgentLoop(initialMessages, streamConsumer, requestContext);

            // 6. 【追加消息到 JSONL】
            sessionManager.appendMessage(sessionKey, "user", content);
            if (result.getContent() != null) {
                sessionManager.appendMessage(sessionKey, "assistant", result.getContent());
            }

            // 7. 更新会话访问时间
            sessionManager.save(session);

            // 8. 【后台检查】在对话结束后，再次检查是否需要压缩
            scheduleBackground(() -> {
                memoryManager.checkAndCompact(session, jsonlStore, "");
            });

            OutboundMessage out = new OutboundMessage(msg.getChannel(), msg.getChatId(), result.getContent());
            out.setMetadata(msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap());
            return out;
        }
    }

    /**
     * 【处理单条入站消息】默认无流式输出。
     */
    public OutboundMessage processMessage(InboundMessage msg) {
        return processMessage(msg, null);
    }

    // ========================================================================
    // 4. 内部处理逻辑 (Internal Logic)
    // ========================================================================

    private boolean handleSpecialCommands(Session session, String cmd, InboundMessage msg) {
        if ("/new".equals(cmd)) {
            // TODO: 实现清空会话功能 - 需要创建新的 JSONL 文件或标记
            publishResponse(msg, "New session started.");
            return true;
        }
        if ("/help".equals(cmd)) {
            publishResponse(msg, "Commands: /new (clear session), /help (this message).");
            return true;
        }
        return false;
    }

    private void publishResponse(InboundMessage msg, String text) {
        OutboundMessage out = new OutboundMessage(msg.getChannel(), msg.getChatId(), text);
        out.setMetadata(msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap());
        bus.publishOutbound(out);
    }

    private Map<String, Object> buildRequestContext(InboundMessage msg) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("_channel", msg.getChannel());
        ctx.put("_chatId", msg.getChatId());
        ctx.put("_metadata", msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap());
        return ctx;
    }


    private OutboundMessage handleSystemMessage(InboundMessage msg) {
        return processMessage(msg, (Consumer<String>) null);
    }

    // ========================================================================
    // 5. Agent 引擎 (Agent Engine)
    // ========================================================================

    /**
     * 【多轮对话引擎】执行 LLM 调用与工具循环。
     */
    public RunResult runAgentLoop(List<Map<String, Object>> initialMessages, Consumer<String> streamConsumer, Map<String, Object> requestContext) {
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);
        List<String> toolsUsed = new ArrayList<>();
        int iter = 0;

        while (iter < maxIterations) {
            iter++;
            log.info("[Agent] === 迭代 {} ===", iter);
            com.simpleclaw.providers.LLMResponse response = provider.chat(
                    messages, toolRegistry.getDefinitions(), model, maxTokens, temperature, streamConsumer);

            // 【详细日志】记录 LLM 响应的所有字段
            if (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty()) {
                log.info("[Agent] 思考过程:\n{}", response.getReasoningContent());
            }
            if (response.getContent() != null && !response.getContent().isEmpty()) {
                log.info("[Agent] 回复内容: {}", response.getContent());
            } else {
                log.warn("[Agent] 回复内容为空");
            }
            
            if (response.hasToolCalls()) {
                log.info("[Agent] 检测到 {} 个工具调用", response.getToolCalls().size());
            } else {
                log.info("[Agent] 无工具调用");
            }
            
            log.info("[Agent] Finish Reason: {}", response.getFinishReason());

            if (response.hasToolCalls()) {
                handleToolCalls(messages, response, toolsUsed, requestContext);
                // 添加反思提示，引导 LLM 根据工具结果生成最终回复
                messages.add(Map.of("role", "user", "content", PromptsConfig.REFLECT_USER_MSG));
            } else {
                // 【检查】如果既没有内容也没有工具调用，说明出错了
                if (response.getContent() == null || response.getContent().isEmpty()) {
                    log.error("[Agent] LLM 返回了空响应（无内容、无工具调用），终止循环");
                    return new RunResult("[Error: LLM returned empty response]", toolsUsed);
                }
                log.info("[Agent] === 完成，迭代次数: {} ===", iter);
                return new RunResult(response.getContent() != null ? response.getContent() : "", toolsUsed);
            }
        }
        log.warn("[Agent] === 达到最大迭代次数 {} ===", maxIterations);
        return new RunResult("[Max tool iterations reached]", toolsUsed);
    }

    public RunResult runAgentLoop(List<Map<String, Object>> initialMessages) {
        return runAgentLoop(initialMessages, null, null);
    }

    public RunResult runAgentLoop(List<Map<String, Object>> initialMessages, Consumer<String> streamConsumer) {
        return runAgentLoop(initialMessages, streamConsumer, null);
    }

    private void handleToolCalls(List<Map<String, Object>> messages, com.simpleclaw.providers.LLMResponse response, List<String> toolsUsed, Map<String, Object> requestContext) {
        log.info("[Agent] 调用工具: {} 个", response.getToolCalls().size());
        List<Map<String, Object>> toolCallsForMessage = new ArrayList<>();
        
        for (ToolCallRequest tc : response.getToolCalls()) {
            // 构造工具调用记录
            Map<String, Object> fn = new HashMap<>();
            fn.put("id", tc.getId());
            fn.put("type", "function");
            Map<String, Object> f = new HashMap<>();
            f.put("name", tc.getName());
            try {
                f.put("arguments", MAPPER.writeValueAsString(tc.getArguments()));
            } catch (Exception e) {
                f.put("arguments", "{}");
            }
            fn.put("function", f);
            toolCallsForMessage.add(fn);
            log.info("[Agent]   - 工具: {}({})", tc.getName(), f.get("arguments"));
        }
        
        contextBuilder.addAssistantMessage(messages, response.getContent(), toolCallsForMessage, response.getReasoningContent());

        // 执行工具并收集结果
        for (ToolCallRequest tc : response.getToolCalls()) {
            Map<String, Object> params = new HashMap<>(tc.getArguments() != null ? tc.getArguments() : Collections.<String, Object>emptyMap());
            if (requestContext != null) {
                params.putAll(requestContext);
            }
            String result = toolRegistry.execute(tc.getName(), params);
            toolsUsed.add(tc.getName());
            log.info("[Agent]   - 结果: {}", result.length() > 200 ? result.substring(0, 200) + "..." : result);
            contextBuilder.addToolResult(messages, tc.getId(), tc.getName(), result);
        }
    }

    // ========================================================================
    // 6. 生命周期与辅助 (Lifecycle & Utils)
    // ========================================================================
    
    /**
     * 【创建消息对象】
     */
    private Map<String, Object> createMessage(String role, String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    public void stop() {
        running.set(false);
        backgroundExecutor.shutdown();
    }

    public void closeMcp() { /* no-op for now */ }
    public void connectMcp() { /* no-op for now */ }

    private void scheduleBackground(Runnable task) {
        backgroundExecutor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Background task failed: {}", e.getMessage());
            }
        });
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
}
