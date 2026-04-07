package com.simpleclaw.memory;

import com.simpleclaw.agent.ContextBuilder;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.Config;
import com.simpleclaw.config.model.ProviderConfig;
import com.simpleclaw.providers.EmbeddingProvider;
import com.simpleclaw.providers.EmbeddingProviderFactory;
import com.simpleclaw.providers.LLMProvider;
import com.simpleclaw.memory.model.MemoryChunk;
import com.simpleclaw.session.JsonlSessionStore;
import com.simpleclaw.session.SessionManager;
import com.simpleclaw.session.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【记忆管理器】
 *
 * 记忆系统的统一管理层，负责协调以下服务：
 * 1. SessionCompactionService - 会话压缩（Token 超限时的 Safeguard 压缩）
 * 2. MemoryFlushService - Memory Flush（保存重要内容到 memory/YYYY-MM-DD.md）
 * 3. MemoryRetrievalService - 记忆检索注入（RAG + 关键词混合检索）
 * 4. VectorMemoryStore - 向量存储
 *
 * 【设计原则】：
 * - 对外提供统一的记忆管理接口
 * - 内部协调各个子服务的工作流程
 * - 避免业务逻辑分散在多个类中
 */
@Slf4j
public class MemoryManager {

    // ========== 依赖组件 ==========

    private final Path workspace;
    private final AgentConfig agentConfig;
    private final LLMProvider llmProvider;
    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final Supplier<List<Map<String, Object>>> getToolDefinitions;

    // ========== 子服务 ==========

    /** 会话压缩服务 */
    private final SessionCompactionService compactionService;

    /** Memory Flush 服务 */
    private final MemoryFlushService flushService;

    /** 记忆检索服务 */
    private final MemoryRetrievalService retrievalService;

    /** 记忆索引服务 */
    private final MemoryIndexService indexService;

    /** 向量存储 */
    private final VectorMemoryStore vectorStore;

    // ========== 状态 ==========

    private final boolean enabled;
    private final Path memoryDir;

    /**
     * 【构造函数】
     *
     * @param workspace 工作空间路径
     * @param agentConfig Agent 配置
     * @param llmProvider LLM 提供者
     * @param contextBuilder 上下文构建器
     * @param sessionManager 会话管理器
     * @param getToolDefinitions 工具定义获取函数
     * @throws SQLException 数据库初始化失败
     */
    public MemoryManager(
            Path workspace,
            AgentConfig agentConfig,
            LLMProvider llmProvider,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            Supplier<List<Map<String, Object>>> getToolDefinitions) throws SQLException {

        this.workspace = workspace;
        this.agentConfig = agentConfig;
        this.llmProvider = llmProvider;
        this.contextBuilder = contextBuilder;
        this.sessionManager = sessionManager;
        this.getToolDefinitions = getToolDefinitions;

        this.memoryDir = workspace.resolve("memory");
        this.enabled = isMemoryEnabled(agentConfig);

        // 【初始化 Embedding 提供商】
        EmbeddingProvider embeddingProvider = enabled ? EmbeddingProviderFactory.fromAgentConfig(agentConfig) : null;

        // 【初始化向量存储】
        this.vectorStore = enabled ? new VectorMemoryStore(workspace, embeddingProvider, agentConfig) : null;

        // 【初始化文档分块器】
        DocumentChunker chunker = enabled ? new DocumentChunker() : null;

        // 【初始化子服务】
        // 【初始化 MemoryIndexService】必须在 flushService 之前
        this.indexService = enabled ? new MemoryIndexService(
                workspace,
                memoryDir,
                chunker,
                vectorStore,
                embeddingProvider
        ) : null;

        this.compactionService = new SessionCompactionService(
                workspace,
                llmProvider,
                agentConfig.getModel(),
                sessionManager,
                agentConfig.getContextWindow(),
                agentConfig,
                contextBuilder,
                getToolDefinitions
        );

        this.flushService = new MemoryFlushService(
                workspace,
                llmProvider,
                agentConfig.getModel(),
                agentConfig,
                contextBuilder,
                indexService
        );

        this.retrievalService = enabled ? new MemoryRetrievalService(
                vectorStore,
                embeddingProvider,
                agentConfig
        ) : null;

        // 【确保记忆目录存在】
        if (enabled) {
            memoryDir.toFile().mkdirs();
            log.info("[MemoryManager] 初始化完成，向量维度: {}", embeddingProvider.getDimension());
        } else {
            log.info("[MemoryManager] 向量记忆功能已禁用");
        }
    }

    /**
     * 【检查记忆功能是否启用】
     * 只要配置了 embedding 提供商的 API Key，就认为记忆功能可用。
     */
    private boolean isMemoryEnabled(Config config) {
        if (config.getProviders() == null) return false;
        
        ProviderConfig embeddingConfig = config.getProviders().get("embedding");
        if (embeddingConfig != null && embeddingConfig.getApiKey() != null && !embeddingConfig.getApiKey().isEmpty()) {
            return true;
        }

        // 兜底：检查环境变量
        String apiKey = System.getenv("OPENAI_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * 【检查记忆功能是否启用】（AgentConfig 版本）
     */
    private boolean isMemoryEnabled(AgentConfig config) {
        if (config.getProviders() == null) return false;
        
        ProviderConfig embeddingConfig = config.getProviders().get("embedding");
        if (embeddingConfig != null && embeddingConfig.getApiKey() != null && !embeddingConfig.getApiKey().isEmpty()) {
            return true;
        }

        // 兜底：检查环境变量
        String apiKey = System.getenv("OPENAI_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * 【获取压缩检查器】
     */
    public CompactionChecker getCompactionChecker() {
        return compactionService.getCompactionChecker();
    }

    /**
     * 【索引记忆目录】
     *
     * 委托给 MemoryIndexService 执行
     */
    public void indexMemoryDirectory() {
        if (!enabled || indexService == null) {
            return;
        }
        indexService.indexMemoryDirectory();
    }

    // ==================== 统一管理层接口 ====================

    /**
     * 【检查并执行会话压缩】
     *
     * 完整的压缩流程：
     * 1. Token 预估（两层架构）
     * 2. 判断是否需要压缩（同时输出上下文窗口日志）
     * 3. 触发 Memory Flush 和压缩（两者同时触发）
     *
     * @param session 当前会话
     * @param messages 内存中的消息列表（用于第二层兜底）
     * @param sessionManager 会话管理器（用于第一层内存缓存）
     * @param newMessageContent 新消息内容（用于日志显示）
     * @return 预估的 Token 总数（用于上层日志记录）
     */
    public int checkAndCompact(Session session, List<Map<String, Object>> messages,
                               SessionManager sessionManager, String newMessageContent) {
        // 【步骤 1】Token 预估：两层递降 Token 估算
        int estimatedTokens = compactionService.getCompactionChecker()
                .calculateProjectedTokens(messages, sessionManager, session.getKey(), newMessageContent);

        // 【步骤 2】判断是否需要压缩，同时获取详细的上下文窗口信息
        CompactionChecker.CompactionCheckResult checkResult = compactionService.getCompactionChecker()
                .checkCompactionNeeded(estimatedTokens, agentConfig.getContextWindow());

//        // 【输出上下文窗口占用日志】
//        logContextWindowUsage(session.getKey(), checkResult);

        if (!checkResult.isNeeded()) {
            return estimatedTokens;
        }

        log.info("[MemoryManager] Session {} 需要压缩，预估 Token 数: {}",
                session.getKey(), estimatedTokens);

        // 【步骤 3】触发 Memory Flush 和压缩（两者同时触发）
        log.info("[MemoryManager] 触发 Memory Flush: session={}", session.getKey());
        flushService.flushMemory(session, sessionManager);
        JsonlSessionStore jsonlStore = sessionManager.getJsonlStore(session.getKey());
        compactionService.maybeConsolidateByTokens(session, jsonlStore);

        // 【步骤 4】压缩后刷新内存中的 entry
        sessionManager.reloadEntries(session.getKey());

        return estimatedTokens;
    }

    /**
     * 【记录上下文窗口占用情况】
     *
     * @param sessionKey 会话键
     * @param result 压缩检查结果
     */
    private void logContextWindowUsage(String sessionKey, CompactionChecker.CompactionCheckResult result) {
        String percentStr = String.format("%.1f%%", result.getUsagePercent());
        log.info("[上下文窗口] 会话: {} | 预估 Token: {}/{} ({}) | 压缩阈值: {} | 距离阈值: {}",
                sessionKey,
                result.getProjectedTokens(),
                result.getContextWindowTokens(),
                percentStr,
                result.getThreshold(),
                result.getMarginToThreshold());
    }

    /**
     * 【注入记忆到 System Prompt】
     *
     * @param systemPrompt 原始 system prompt
     * @param query        用户查询
     * @return 注入记忆后的 system prompt
     */
    public String injectMemory(String systemPrompt, String query) {
        if (!enabled || retrievalService == null) {
            return systemPrompt;
        }
        return retrievalService.injectMemory(systemPrompt, query);
    }

    /**
     * 【搜索记忆】
     *
     * 供 memory_search 工具调用，执行混合检索。
     *
     * @param query      搜索查询
     * @param maxResults 最大结果数（可选，使用默认配置）
     * @param minScore   最低分数阈值（可选，使用默认配置）
     * @return 记忆块列表
     */
    public List<MemoryChunk> searchMemory(String query, Integer maxResults, Float minScore) throws SQLException {
        if (!enabled || vectorStore == null) {
            return Collections.emptyList();
        }

        int topK = maxResults != null ? maxResults : 6;
        float threshold = minScore != null ? minScore : 0.35f;

        return vectorStore.hybridSearch(query, topK, threshold);
    }


    /**
     * 【检查是否启用】
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ========== 每日记忆文件操作 ==========

    // 【每日记忆文件日期格式】YYYY-MM-DD
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;

    // 【时间戳格式】HH:mm
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 【追加每日记忆】
     *
     * 将记忆内容追加到 memory/YYYY-MM-DD.md 文件。
     * 如果文件已存在则追加，不存在则创建。
     *
     * 文件结构：
     * workspace/
     * ├── MEMORY.md          # 用户手动维护（只读）
     * └── memory/            # 自动生成的每日记忆目录
     *     ├── 2026-04-01.md
     *     ├── 2026-04-02.md
     *     └── 2026-04-03.md
     *
     * @param content 记忆内容（Markdown 格式）
     * @return 写入的文件路径
     */
    public Path appendDailyMemory(String content) {
        if (content == null || content.trim().isEmpty()) {
            log.debug("[MemoryManager] 记忆内容为空，跳过写入");
            return null;
        }

        try {
            // 【生成日期文件名】memory/YYYY-MM-DD.md
            String dateStamp = LocalDate.now().format(DATE_FORMATTER);
            Path dailyFile = memoryDir.resolve(dateStamp + ".md");

            // 【确保目录存在】
            Files.createDirectories(memoryDir);

            // 【生成时间戳】
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);

            // 【构建记忆条目】
            StringBuilder entry = new StringBuilder();

            // 如果是新文件，添加文件头
            if (!Files.exists(dailyFile)) {
                entry.append("# Daily Memory - ").append(dateStamp).append("\n\n");
            }

            // 添加时间戳和内容
            entry.append("## ").append(timestamp).append("\n\n");
            entry.append(content.trim()).append("\n\n");
            entry.append("---\n\n");

            // 【追加写入文件】
            Files.writeString(
                    dailyFile,
                    entry.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

            log.info("[MemoryManager] 每日记忆已追加: {}", dailyFile.getFileName());

            // 【同时索引到向量存储】用于后续检索
            indexDailyMemoryFile(dailyFile);

            return dailyFile;

        } catch (IOException e) {
            log.error("[MemoryManager] 追加每日记忆失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 【索引每日记忆文件】
     *
     * 将每日记忆文件分块并索引到向量存储，用于语义检索。
     *
     * @param file 每日记忆文件路径
     */
    private void indexDailyMemoryFile(Path file) {
        if (!enabled || vectorStore == null) {
            return;
        }

        try {
            // 【使用 MemoryIndexService 重新索引整个文件】
            indexService.indexFile(file);

            log.debug("[MemoryManager] 每日记忆文件已重新索引: {}",
                    file.getFileName());

        } catch (Exception e) {
            log.error("[MemoryManager] 索引每日记忆文件失败: {}", e.getMessage());
        }
    }


    /**
     * 【关闭资源】
     */
    public void close() {
        if (vectorStore != null) {
            vectorStore.close();
        }
        log.info("[MemoryManager] 已关闭");
    }
}
