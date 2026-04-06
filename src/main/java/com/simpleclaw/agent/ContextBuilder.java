package com.simpleclaw.agent;

import com.simpleclaw.agent.skills.SkillsLoader;
import com.simpleclaw.agent.skills.model.Skill;
import com.simpleclaw.config.model.PromptsConfig;
import com.simpleclaw.config.model.SkillsConfig;
import com.simpleclaw.session.JsonlSessionStore;
import com.simpleclaw.session.model.SessionEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【上下文构建器】
 *
 * 统一负责所有 Context 的组装：
 * 1. 对话上下文（AgentLoop 使用）
 * 2. 压缩提示上下文（SessionCompactionService 使用）
 * 3. 记忆提取上下文（MemoryFlushService 使用）
 * 4. Token 估算消息列表（CompactionChecker 使用）
 *
 * 【长期记忆系统】
 * 长期记忆不再自动注入 System Prompt，改为：
 * - System Prompt 中包含 Memory Recall 节（PromptsConfig.SYSTEM_PROMPT）
 * - LLM 通过 memory_search 工具主动搜索记忆
 * - LLM 通过 memory_get 工具读取具体文件内容
 */
public class ContextBuilder {

    private static final String[] BOOTSTRAP_FILES = {"AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md", "IDENTITY.md"};

    /** Prompt 中技能部分的最大字符预算（默认 30000） */
    private static final int DEFAULT_SKILLS_PROMPT_CHARS = 30000;

    private final Path workspace;
    private final Path dataDir;
    private final Path builtinSkillsDir;
    private final SkillsConfig skillsConfig;
    private SkillsLoader skillsLoader;

    /**
     * 【基础构造函数】
     * @param workspace 工作区路径
     */
    public ContextBuilder(Path workspace) {
        this(workspace, null, null, new SkillsConfig());
    }

    /**
     * 【完整构造函数】
     * @param workspace 工作区路径
     * @param dataDir 数据目录（~/.simpleclaw）
     * @param builtinSkillsDir 内置技能目录
     * @param skillsConfig 技能配置
     */
    public ContextBuilder(Path workspace, Path dataDir, Path builtinSkillsDir, SkillsConfig skillsConfig) {
        this.workspace = workspace;
        this.dataDir = dataDir;
        this.builtinSkillsDir = builtinSkillsDir;
        this.skillsConfig = skillsConfig != null ? skillsConfig : new SkillsConfig();
        this.skillsLoader = new SkillsLoader(workspace, dataDir, builtinSkillsDir, this.skillsConfig);
    }

    /**
     * 【设置自定义 SkillsLoader】
     * 用于测试或特殊场景
     */
    public void setSkillsLoader(SkillsLoader skillsLoader) {
        this.skillsLoader = skillsLoader;
    }

    // ==================== 统一 Context 构建入口 ====================

    /**
     * 【构建对话上下文】AgentLoop 使用
     *
     * 组装完整的发送给 LLM 的消息列表：
     * 1. 系统提示（基础信息 + bootstrap + skills）
     * 2. 会话历史（从 JSONL 读取，包含压缩摘要）
     * 3. 当前用户消息
     *
     * @param jsonlStore JSONL 会话存储
     * @param currentMessage 当前用户消息
     * @param skillNames 启用的技能列表
     * @return 完整的消息列表
     */
    public List<Map<String, Object>> buildChatContext(JsonlSessionStore jsonlStore,
                                                       String currentMessage,
                                                       List<String> skillNames) {
        // 【步骤 1】构建系统提示
        String systemPrompt = buildBaseSystemPrompt(skillNames);

        List<Map<String, Object>> messages = new ArrayList<>();

        // 添加系统消息
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // 【步骤 2】从 JSONL 获取会话历史（包含压缩摘要处理）
        List<Map<String, Object>> history = buildHistoryFromJsonl(jsonlStore);
        messages.addAll(history);

        // 【步骤 3】添加当前用户消息
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", currentMessage != null ? currentMessage : "");
        messages.add(userMsg);

        return messages;
    }

    /**
     * 【构建压缩提示上下文】SessionCompactionService 使用
     *
     * 用于生成压缩摘要的提示：
     * 1. 压缩指令系统提示
     * 2. 要压缩的消息块
     *
     * @param messagesToCompact 要压缩的消息块
     * @return 压缩提示消息列表
     */
    public List<Map<String, Object>> buildCompactionContext(List<Map<String, Object>> messagesToCompact) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 【步骤 1】添加压缩指令
        String compactionInstruction = PromptsConfig.SESSION_COMPACTION_SYSTEM_PROMPT;
        messages.add(Map.of("role", "system", "content", compactionInstruction));

        // 【步骤 2】添加要压缩的消息
        if (messagesToCompact != null) {
            for (Map<String, Object> msg : messagesToCompact) {
                messages.add(new HashMap<>(msg));
            }
        }

        return messages;
    }

    /**
     * 【构建记忆提取上下文】MemoryFlushService 使用
     *
     * 用于生成长期记忆的提示：
     * 1. Memory Flush 系统提示
     * 2. 会话历史（从 JSONL 读取）
     *
     * @param jsonlStore JSONL 会话存储
     * @param dateStamp 日期戳
     * @return 记忆提取提示消息列表
     */
    public List<Map<String, Object>> buildMemoryFlushContext(JsonlSessionStore jsonlStore,
                                                              String dateStamp) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 【步骤 1】添加 Memory Flush 系统提示
        String systemPrompt = String.format(
                PromptsConfig.MEMORY_FLUSH_SYSTEM_PROMPT_TEMPLATE,
                dateStamp, dateStamp
        );
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 【步骤 2】从 JSONL 获取会话历史
        List<Map<String, Object>> history = buildHistoryFromJsonl(jsonlStore);
        messages.addAll(history);

        return messages;
    }

    /**
     * 【获取原始消息列表】CompactionChecker 使用
     *
     * 仅返回从 JSONL 读取的消息列表（不含系统提示），用于 Token 估算
     *
     * @param jsonlStore JSONL 会话存储
     * @return 消息列表（包含压缩摘要处理）
     */
    public List<Map<String, Object>> getMessagesForTokenEstimation(JsonlSessionStore jsonlStore) {
        return buildHistoryFromJsonl(jsonlStore);
    }

    // ==================== 内部方法 ====================

    /**
     * 【从 JSONL 构建历史消息】
     *
     * 核心逻辑：
     * 1. 查找最后一个 compaction entry
     * 2. 如果有 compaction，注入摘要 + 从 firstKeptEntryId 开始的消息
     * 3. 如果没有 compaction，返回所有消息
     *
     * @param jsonlStore JSONL 会话存储
     * @return 历史消息列表（不含系统提示）
     */
    private List<Map<String, Object>> buildHistoryFromJsonl(JsonlSessionStore jsonlStore) {
        List<SessionEntry> allEntries = jsonlStore.readAllEntries();

        if (allEntries.isEmpty()) {
            return Collections.emptyList();
        }

        // 查找最后一个 compaction
        SessionEntry lastCompaction = findLastCompaction(allEntries);

        List<Map<String, Object>> messages = new ArrayList<>();

        if (lastCompaction != null) {
            // 【注入压缩摘要】
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "[Previous Session Summary]\n\n" + lastCompaction.getSummary());
            messages.add(summaryMsg);

            // 【从 firstKeptEntryId 开始读取消息】
            String firstKeptId = lastCompaction.getFirstKeptEntryId();
            boolean found = false;

            for (SessionEntry entry : allEntries) {
                if (entry.getId().equals(firstKeptId)) {
                    found = true;
                }

                if (found && entry.isMessage()) {
                    messages.add(entry.toMessageMap());
                }
            }
        } else {
            // 【没有压缩过，返回所有消息】
            for (SessionEntry entry : allEntries) {
                if (entry.isMessage()) {
                    messages.add(entry.toMessageMap());
                }
            }
        }

        return messages;
    }

    /**
     * 【查找最后一个压缩标记】
     *
     * @param entries 所有 entries
     * @return 最后一个 compaction entry，如果没有则返回 null
     */
    private SessionEntry findLastCompaction(List<SessionEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).isCompaction()) {
                return entries.get(i);
            }
        }
        return null;
    }

    /**
     * 【组装基础系统提示】
     *
     * 构建不含长期记忆的基础 system prompt，包含：
     * - 基础信息（时间、工作空间）
     * - 系统规则（SYSTEM_PROMPT，已包含 Memory Recall 节）
     * - Bootstrap 文件（AGENTS.md, SOUL.md 等）
     * - 技能定义（三段式降级：全格式 → 紧凑格式 → 截断）
     *
     * 注意：长期记忆不再自动注入，由 LLM 通过 memory_search/memory_get 工具主动检索
     *
     * @param skillFilter 代理级技能过滤器（可选），仅保留指定名称的技能
     */
    public String buildBaseSystemPrompt(List<String> skillFilter) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current time: ").append(ZonedDateTime.now()).append("\n\n");
        sb.append("Workspace: ").append(workspace).append("\n\n");

        // SYSTEM_PROMPT 已包含 Memory Recall 节
        sb.append(PromptsConfig.SYSTEM_PROMPT);

        // 【Bootstrap 文件】
        for (String name : BOOTSTRAP_FILES) {
            Path p = workspace.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    sb.append("--- ").append(name).append(" ---\n").append(content).append("\n\n");
                } catch (Exception e) {
                    // skip
                }
            }
        }

        // 【技能定义】使用三段式降级策略
        List<Skill> enabledSkills = skillsLoader.getEnabledSkills(skillFilter);
        if (!enabledSkills.isEmpty()) {
            int maxChars = skillsConfig.getMaxSkillsPromptChars() > 0
                    ? skillsConfig.getMaxSkillsPromptChars()
                    : DEFAULT_SKILLS_PROMPT_CHARS;
            String skillsPrompt = skillsLoader.formatSkillsForPrompt(enabledSkills, maxChars);
            if (!skillsPrompt.isEmpty()) {
                sb.append("\n").append(skillsPrompt).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 【构建消息列表】返回给 LLM 的消息列表：[system, ...history, userMessage]
     *
     * 【长期记忆检索说明】
     * 长期记忆不再自动注入 System Prompt。改为：
     * 1. 在 System Prompt 中添加 Memory Recall 节，指导 LLM 主动使用工具
     * 2. LLM 通过 memory_search 工具执行混合检索（向量 + BM25）
     * 3. LLM 通过 memory_get 工具读取具体文件内容
     *
     * 这种方式让模型自主决定何时、读取什么记忆，更加灵活。
     *
     * @param history       历史消息列表
     * @param currentMessage 当前用户消息
     * @param skillFilter   代理级技能过滤器（可选）
     * @param media         媒体文件列表
     * @param channel       频道标识
     * @param chatId        聊天ID
     * @return 完整的消息列表
     */
    public List<Map<String, Object>> buildMessages(List<Map<String, Object>> history,
                                                   String currentMessage,
                                                   List<String> skillFilter,
                                                   List<String> media,
                                                   String channel,
                                                   String chatId) {
        // 【构建系统提示】包含 Memory Recall 节，指导 LLM 使用记忆工具
        String system = buildBaseSystemPrompt(skillFilter);

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", system);
        messages.add(systemMsg);

        if (history != null) {
            for (Map<String, Object> m : history) {
                messages.add(new HashMap<>(m));
            }
        }

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", currentMessage != null ? currentMessage : "");
        messages.add(userMsg);

        return messages;
    }

    /** 向消息列表追加一条 tool 结果 */
    public void addToolResult(List<Map<String, Object>> messages, String toolCallId, String toolName, String result) {
        Map<String, Object> tr = new HashMap<>();
        tr.put("role", "tool");
        tr.put("tool_call_id", toolCallId);
        tr.put("content", result != null ? result : "");
        messages.add(tr);
    }

    /** 向消息列表追加一条 assistant 消息（可含 tool_calls、reasoning_content） */
    public void addAssistantMessage(List<Map<String, Object>> messages, String content,
                                    List<Map<String, Object>> toolCalls, String reasoningContent) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        if (content != null && !content.isEmpty()) {
            msg.put("content", content);
        } else {
            msg.put("content", "");
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls);
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            msg.put("reasoning_content", reasoningContent);
        }
        messages.add(msg);
    }
}
