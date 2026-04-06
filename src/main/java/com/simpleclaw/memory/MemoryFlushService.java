package com.simpleclaw.memory;

import com.simpleclaw.agent.ContextBuilder;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.model.PromptsConfig;
import com.simpleclaw.providers.LLMProvider;
import com.simpleclaw.providers.LLMResponse;
import com.simpleclaw.session.JsonlSessionStore;
import com.simpleclaw.session.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【Memory Flush 服务】
 *
 * 负责在会话压缩前执行 Memory Flush，将重要的会话内容保存到日期命名的 Markdown 文件中。
 *
 * 【与会话压缩的区别】：
 * | 特性 | 会话压缩 | Memory Flush |
 * |------|---------|-------------|
 * | 触发时机 | Token 超限时 | Token 接近超限前 |
 * | 产物位置 | sess-*.jsonl | memory/YYYY-MM-DD.md |
 * | 产物格式 | JSONL entry | Markdown（用户可见）|
 * | 写入者 | 系统（LLM 生成摘要）| Agent 自主决定 |
 * | 跨会话 | 否 | 是（永久保存）|
 *
 * 【执行流程】：
 * 1. 构建包含上次压缩摘要 + 最近会话历史的 Prompt
 * 2. 调用 LLM 生成要保存的记忆内容
 * 3. 将内容追加写入 memory/YYYY-MM-DD.md 文件
 */
@Slf4j
public class MemoryFlushService {

    /** 日期格式：YYYY-MM-DD */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Memory 文件目录名 */
    private static final String MEMORY_DIR = "memory";

    // ========== 依赖组件 ==========

    private final Path workspace;
    private final LLMProvider provider;
    private final String model;
    private final AgentConfig agentConfig;
    private final ContextBuilder contextBuilder;

    /**
     * 【构造函数】
     *
     * @param workspace 工作空间路径
     * @param provider LLM 提供者
     * @param model 模型名称
     * @param agentConfig Agent 配置
     * @param contextBuilder 上下文构建器
     */
    public MemoryFlushService(
            Path workspace,
            LLMProvider provider,
            String model,
            AgentConfig agentConfig,
            ContextBuilder contextBuilder) {
        this.workspace = workspace;
        this.provider = provider;
        this.model = model;
        this.agentConfig = agentConfig;
        this.contextBuilder = contextBuilder;
    }

    // ========== 主入口 ==========

    /**
     * 【执行 Memory Flush】
     *
     * Agent 基于会话历史自主决定保存什么内容到 memory/YYYY-MM-DD.md
     *
     * 【处理流程】：
     * 1. 获取当前日期戳
     * 2. 构建消息列表：System Prompt + 会话历史（压缩摘要 + 有效消息）
     * 3. 调用 LLM 生成记忆内容
     * 4. 检查 <SILENT> 标记
     * 5. 追加写入 memory/YYYY-MM-DD.md
     *
     * @param session 当前会话
     * @param jsonlStore JSONL 会话存储
     * @return 是否成功
     */
    public boolean flushMemory(Session session, JsonlSessionStore jsonlStore) {
        try {
            // 【步骤 1】获取当前日期戳
            String dateStamp = getCurrentDateStamp();

            // 【步骤 2】使用 ContextBuilder 构建记忆提取上下文
            List<Map<String, Object>> messages = contextBuilder.buildMemoryFlushContext(jsonlStore, dateStamp);

            // 【步骤 3】调用 LLM 生成记忆内容
            String memoryContent = generateMemoryContent(messages);

            if (memoryContent == null || memoryContent.isEmpty()) {
                log.warn("[MemoryFlush] LLM 返回空内容，跳过保存");
                return false;
            }

            // 【步骤 4】检查 <SILENT> 标记（表示无需保存）
            if (memoryContent.trim().equals("<SILENT>")) {
                log.info("[MemoryFlush] Agent 标记无需保存内容");
                return true;
            }

            // 【步骤 5】追加写入文件
            Path memoryFile = getMemoryFilePath();
            appendToMemoryFile(memoryFile, memoryContent, session.getKey());

            log.info("[MemoryFlush] 成功保存记忆到: {}", memoryFile);
            return true;

        } catch (Exception e) {
            log.error("[MemoryFlush] 执行失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 【判断是否需要触发 Memory Flush】
     *
     * 触发条件：
     * Token 数量 >= 上下文窗口 - 预留空间 - 安全余量 - 软阈值
     *
     * 软阈值用于在 Token 接近超限时提前触发，给 Agent 机会保存重要内容
     *
     * @param currentTokens 当前 Token 数
     * @param contextWindow 上下文窗口大小
     * @return true 如果需要触发 Memory Flush
     */
    public boolean isMemoryFlushNeeded(int currentTokens, int contextWindow) {
        // 软阈值：默认 4000 tokens，可在配置中调整
        int softThreshold = 4000;

        int threshold = contextWindow
                - agentConfig.getReserveTokens()
                - agentConfig.getSafetyMargin()
                - softThreshold;

        boolean needed = currentTokens >= threshold;

        log.debug("[MemoryFlush] 触发检查: currentTokens={}, threshold={}, needed={}",
                currentTokens, threshold, needed);

        return needed;
    }

    // ========== 内部方法 ==========

    /**
     * 【生成记忆内容】
     *
     * 调用 LLM 生成要保存的记忆内容
     *
     * @param messages 已构建好的消息列表（包含 system prompt + 会话历史）
     * @return 生成的记忆内容
     */
    private String generateMemoryContent(List<Map<String, Object>> messages) {
        // 调用 LLM
        LLMResponse response = provider.chat(
                messages,
                null,  // 不使用工具
                model,
                2048,  // 最大生成 Token 数
                0.3,   // 低温度，确保输出稳定
                null
        );

        return response.getContent();
    }

    /**
     * 【获取当前日期戳】
     *
     * @return 日期戳（YYYY-MM-DD）
     */
    private String getCurrentDateStamp() {
        return LocalDate.now(ZoneId.systemDefault()).format(DATE_FORMATTER);
    }

    /**
     * 【获取 Memory 文件路径】
     *
     * 格式：workspace/memory/YYYY-MM-DD.md
     *
     * @return Memory 文件路径
     */
    private Path getMemoryFilePath() {
        // 使用当前日期（系统时区）
        String dateStamp = LocalDate.now(ZoneId.systemDefault()).format(DATE_FORMATTER);

        Path memoryDir = workspace.resolve(MEMORY_DIR);
        return memoryDir.resolve(dateStamp + ".md");
    }

    /**
     * 【追加内容到 Memory 文件】
     *
     * 如果文件不存在则创建，存在则追加写入
     *
     * @param memoryFile Memory 文件路径
     * @param content 要追加的内容
     * @param sessionKey 会话键（用于标记来源）
     * @throws IOException 写入失败时抛出
     */
    private void appendToMemoryFile(Path memoryFile, String content, String sessionKey) throws IOException {
        // 确保目录存在
        Files.createDirectories(memoryFile.getParent());

        StringBuilder entry = new StringBuilder();

        // 如果是新文件，添加文件头
        boolean isNewFile = !Files.exists(memoryFile);
        if (isNewFile) {
            String dateStamp = LocalDate.now(ZoneId.systemDefault()).format(DATE_FORMATTER);
            entry.append("# Memory Log - ").append(dateStamp).append("\n\n");
            entry.append("This file contains durable memories extracted from conversations.").append("\n\n");
            entry.append("---\n\n");
        }

        // 添加时间戳和会话来源
        entry.append("## Entry - ").append(java.time.LocalDateTime.now().toString()).append("\n\n");
        entry.append("**Source:** ").append(sessionKey).append("\n\n");

        // 添加内容
        entry.append(content);
        entry.append("\n\n---\n\n");

        // 追加写入文件
        Files.writeString(
                memoryFile,
                entry.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );

        log.debug("[MemoryFlush] 已追加内容到: {} ({} bytes)",
                memoryFile, entry.length());
    }
}
