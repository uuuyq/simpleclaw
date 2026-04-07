package com.simpleclaw.memory;

import com.simpleclaw.agent.ContextBuilder;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.model.PromptsConfig;
import com.simpleclaw.providers.LLMProvider;
import com.simpleclaw.session.JsonlSessionStore;
import com.simpleclaw.session.SessionManager;
import com.simpleclaw.session.model.Session;
import com.simpleclaw.session.model.SessionEntry;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

/**
 * 【会话压缩服务】
 *
 * 负责会话历史压缩，当 Token 数超过预算时将旧消息归档为结构化摘要。
 *
 * 【核心功能】：
 * 1. 基于 Token 预算的压缩触发判断
 * 2. 选择压缩边界（在用户消息边界处切割）
 * 3. 调用 LLM 生成 5-section Safeguard 摘要
 * 4. 质量审核与重试机制
 * 5. 将摘要写入 JSONL 作为 compaction entry
 *
 * 【压缩流程】：
 * 1. 从 JSONL 读取消息列表（自动跳过已压缩部分）
 * 2. 估算当前 Token 数，判断是否超过安全预算
 * 3. 选择压缩边界（endIdx），将消息分为 chunk[0,endIdx) 和保留部分[endIdx,..)
 * 4. 调用 LLM 生成 chunk 的 Safeguard 摘要
 * 5. 将摘要 + firstKeptEntryId 写入 JSONL
 * 6. 重新读取消息列表，继续下一轮压缩（如需要）
 *
 * 【关键概念】：
 * - firstKeptEntryId: 压缩后第一个保留的消息 ID，用于后续读取时定位
 * - Safeguard 摘要: 包含 Decisions/TODOs/Constraints/Pending asks/Identifiers 五部分
 * - 安全预算: contextWindow - reserveTokens - safetyMargin
 *
 * 【注意】：Memory Flush 由 MemoryManager 统一调度，不在此服务中执行
 */
@Slf4j
public class SessionCompactionService {

    // ========== 依赖组件 ==========

    /** 工作空间路径 */
    private final Path workspace;
    /** LLM 提供者 */
    private final LLMProvider provider;
    /** 使用的模型名称 */
    private final String model;
    /** 会话管理器 */
    private final SessionManager sessions;
    /** 上下文窗口大小（Token 数） */
    private final int contextWindowTokens;
    /** Agent 配置 */
    private final AgentConfig agentConfig;
    /** 上下文构建器 */
    private final ContextBuilder contextBuilder;
    /** 工具定义获取函数 */
    private final Supplier<List<Map<String, Object>>> getToolDefinitions;

    // ========== 子服务 ==========

    /** 压缩判断器（轻量级预检 + 重量级精算） */
    private final CompactionChecker compactionChecker;

    // ========== 并发控制 ==========

    /** 会话锁映射，使用弱引用避免内存泄漏 */
    private final Map<String, WeakReference<ReentrantLock>> locks = new ConcurrentHashMap<>();
    /** 压缩状态追踪，防止后台压缩与前台请求冲突 */
    private final Map<String, Boolean> compactingSessions = new ConcurrentHashMap<>();

    /**
     * 【构造函数】
     *
     * @param workspace 工作空间路径
     * @param provider LLM 提供者
     * @param model 模型名称
     * @param sessions 会话管理器
     * @param contextWindowTokens 上下文窗口大小（Token 数）
     * @param agentConfig Agent 配置
     * @param contextBuilder 上下文构建器
     * @param getToolDefinitions 工具定义获取函数
     */
    public SessionCompactionService(
            Path workspace,
            LLMProvider provider,
            String model,
            SessionManager sessions,
            int contextWindowTokens,
            AgentConfig agentConfig,
            ContextBuilder contextBuilder,
            Supplier<List<Map<String, Object>>> getToolDefinitions) {
        this.workspace = workspace;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.agentConfig = agentConfig;
        this.contextBuilder = contextBuilder;
        this.getToolDefinitions = getToolDefinitions;

        // 初始化子服务
        this.compactionChecker = new CompactionChecker(agentConfig);
    }

    /**
     * 【获取会话压缩锁】
     *
     * 每个会话有独立的锁，防止同一会话的并发压缩操作冲突。
     * 使用 WeakReference 避免锁对象长期占用内存。
     *
     * @param sessionKey 会话键 (channel:chatId)
     * @return 对应会话的 ReentrantLock
     */
    public ReentrantLock getLock(String sessionKey) {
        return locks.compute(sessionKey, (k, v) -> {
            if (v == null || v.get() == null) {
                return new WeakReference<>(new ReentrantLock());
            }
            return v;
        }).get();
    }

    /**
     * 【执行 Safeguard 压缩】
     *
     * 将选定的消息块压缩为结构化摘要，并写入 JSONL。
     *
     * 【处理流程】：
     * 1. 使用 ContextBuilder 构建压缩 Prompt
     * 2. 调用 LLM 生成 5-section 摘要（带质量审核和重试）
     * 3. 将摘要作为 compaction entry 追加到 JSONL 和内存
     *
     * @param messages 要压缩的消息列表（chunk[0,endIdx)）
     * @param firstKeptEntryId 压缩后第一个保留的消息 ID（即 messages[endIdx] 的 ID）
     * @param sessionKey 会话键 (channel:chatId)
     * @param totalTokens 压缩后的总 Token 数（systemPrompt + 摘要）
     * @return 异步任务，返回是否成功归档
     */
    public CompletableFuture<Boolean> consolidateMessages(
            List<Map<String, Object>> messages,
            String firstKeptEntryId,
            String sessionKey,
            int threshold) {

        // 【解析 sessionKey 获取 channel 和 chatId】
        String[] parts = sessionKey.split(":", 2);
        String channel = parts.length > 0 ? parts[0] : "unknown";
        String chatId = parts.length > 1 ? parts[1] : "default";

        return CompletableFuture.supplyAsync(() -> {
            if (messages == null || messages.isEmpty()) {
                return true;
            }

            // 【步骤 1】使用 ContextBuilder 构建压缩提示
            List<Map<String, Object>> promptMessages = contextBuilder.buildCompactionContext(messages);

            // 【步骤 2】计算 maxOutputTokens = threshold * factor（用于 prompt 控制）
            double factor = agentConfig.getCompactionOutputFactor();
            int maxOutputTokens = (int) (threshold * factor);

            // 【步骤 3】调用 LLM 生成摘要（带质量审核和重试）
            // chat 的 maxTokens 使用 threshold（硬上限），prompt 中使用 maxOutputTokens（软性约束）
            SummaryResult result = generateCompactionSummaryWithQualityCheck(promptMessages, maxOutputTokens, threshold);

            if (result == null || result.getSummary() == null) {
                log.error("[Consolidator] Failed to generate compaction summary after retries");
                return false;
            }

            // 【步骤 4】计算 totalTokens = completion_tokens + header_tokens
            // completion_tokens 是 LLM 实际生成的摘要长度
            // header_tokens 从 PromptsConfig 获取
            Integer completionTokens = result.getCompletionTokens();
            if (completionTokens == null || completionTokens <= 0) {
                // 如果 LLM 没有返回，使用启发式估算
                throw new RuntimeException("[Consolidator] LLM did not return completion_tokens");
            }
            int totalTokens = com.simpleclaw.config.model.PromptsConfig.calculateCompactionTotalTokens(completionTokens);

            // 【步骤 5】追加 compaction entry 到 JSONL 和内存
            sessions.appendCompaction(sessionKey, result.getSummary(), firstKeptEntryId, totalTokens);

            log.info("[Consolidator] Successfully compacted session {}: completionTokens={}, totalTokens={}, firstKeptId={}",
                    sessionKey, completionTokens, totalTokens, firstKeptEntryId);

            return true;
        });
    }

    /**
     * 【摘要生成结果】
     */
    private static class SummaryResult {
        private final String summary;
        private final Integer completionTokens;  // 模型生成的摘要 tokens

        public SummaryResult(String summary, Integer completionTokens) {
            this.summary = summary;
            this.completionTokens = completionTokens;
        }

        public String getSummary() {
            return summary;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }
    }

    /**
     * 【带质量审核的摘要生成】
     *
     * @param promptMessages 构建好的 Prompt 消息列表
     * @param targetTokens 目标输出 tokens（用于 prompt 中的软性约束）
     * @param maxTokens 最大输出 tokens（传给 LLM 的硬上限）
     * @return 生成的摘要和 Token 使用情况，失败返回 null
     */
    private SummaryResult generateCompactionSummaryWithQualityCheck(
            List<Map<String, Object>> promptMessages,
            int targetTokens,
            int maxTokens) {

        int maxRetries = agentConfig.getQualityGuardMaxRetries();
        String feedback = null;
        Integer lastCompletionTokens = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 复制消息列表，避免修改原始列表
            List<Map<String, Object>> chatMessages = new ArrayList<>(promptMessages);

            // 【添加长度约束到 Prompt】
            // 在第一条 system message 后添加长度约束
            if (!chatMessages.isEmpty() && "system".equals(chatMessages.get(0).get("role"))) {
                String originalContent = (String) chatMessages.get(0).get("content");
                String constrainedContent = originalContent + String.format(
                    "\n\n【长度约束】请确保生成的摘要简洁，控制在约 %d tokens 以内。",
                    targetTokens
                );
                chatMessages.set(0, Map.of("role", "system", "content", constrainedContent));
            }

            // 如果有反馈，附加到最后一条 user prompt
            if (feedback != null) {
                String feedbackPrompt = String.format(
                    PromptsConfig.QUALITY_CHECK_FEEDBACK_TEMPLATE,
                    feedback
                );
                // 添加反馈作为新的 user 消息
                chatMessages.add(Map.of("role", "user", "content", feedbackPrompt));
            }

            // 调用 LLM，maxTokens 使用 threshold（硬上限）
            com.simpleclaw.providers.LLMResponse response = provider.chat(
                chatMessages, null, model, maxTokens, 0.3, null
            );

            // 记录 completion_tokens（模型生成的摘要长度）
            if (response.getCompletionTokens() > 0) {
                lastCompletionTokens = response.getCompletionTokens();
            }

            String summary = response.getContent();
            if (summary == null || summary.isEmpty()) {
                log.warn("[Consolidator] Empty summary from LLM, attempt {}/{}", attempt + 1, maxRetries + 1);
                continue;
            }

            // 质量审核
            QualityCheckResult check = auditSummaryQuality(summary);
            if (check.isValid()) {
                return new SummaryResult(summary, lastCompletionTokens);
            }

            // 准备反馈用于重试
            feedback = check.getFeedback();
            log.warn("[Consolidator] Quality check failed (attempt {}/{}): {}",
                    attempt + 1, maxRetries + 1, feedback);
        }

        // 所有重试都失败
        log.error("[Consolidator] All quality check retries exhausted");
        return null;
    }
    
    /**
     * 【质量审核结果】
     */
    private static class QualityCheckResult {
        private final boolean valid;
        private final String feedback;
        
        public QualityCheckResult(boolean valid, String feedback) {
            this.valid = valid;
            this.feedback = feedback;
        }
        
        public boolean isValid() { return valid; }
        public String getFeedback() { return feedback; }
    }
    
    /**
     * 【质量审核】
     * 验证 5 个必需章节是否存在
     */
    private QualityCheckResult auditSummaryQuality(String summary) {
        List<String> missingSections = new ArrayList<>();

        // 检查必需章节
        String[] requiredSections = {
            "## Decisions",
            "## Open TODOs",
            "## Constraints/Rules",
            "## Pending user asks",
            "## Exact identifiers"
        };

        for (String section : requiredSections) {
            if (!summary.contains(section)) {
                missingSections.add(section);
            }
        }

        if (!missingSections.isEmpty()) {
            return new QualityCheckResult(false,
                "missing_sections:" + String.join(",", missingSections));
        }

        return new QualityCheckResult(true, null);
    }
    
    /**
     * 【估算消息 Token 数】
     */
    private int estimateMessagesTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            String content = (String) msg.getOrDefault("content", "");
            total += content.length() / 4; // 粗略估算：1 token ≈ 4 chars
        }
        return total;
    }
    
    /**
     * 【估算摘要 Token 数】
     */
    private int estimateSummaryTokens(String summary) {
        return summary.length() / 4;
    }
    
    /**
     * 【获取指定索引消息的 ID】
     *
     * @param messages 消息列表
     * @param index 索引位置
     * @return 消息 ID，如果索引越界或消息无 ID 则返回 null
     */
    private String getMessageIdAtIndex(List<Map<String, Object>> messages, int index) {
        if (index < 0 || index >= messages.size()) {
            return null;
        }
        Map<String, Object> msg = messages.get(index);
        Object id = msg.get("id");
        return id != null ? id.toString() : null;
    }

    /**
     * 【获取 Entry 列表中指定索引的 Entry ID】
     *
     * 跳过非 MESSAGE 类型的 entry，找到第 messageIndex 个消息 entry
     *
     * @param entries Entry 列表
     * @param messageIndex 消息索引（只计数 MESSAGE 类型）
     * @return Entry ID，如果找不到则返回 null
     */
    private String getEntryIdAtIndex(List<SessionEntry> entries, int messageIndex) {
        int msgCount = 0;
        for (SessionEntry entry : entries) {
            if (entry.isMessage()) {
                if (msgCount == messageIndex) {
                    return entry.getId();
                }
                msgCount++;
            }
        }
        return null;
    }

    /**
     * 【估算 Entry 列表的 Token 数】
     *
     * 只计算最后一个 compaction 之后的 MESSAGE 类型 entry
     */
    private int estimateEntriesTokens(List<SessionEntry> entries) {
        // 找到最后一个 compaction
        SessionEntry lastCompaction = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).isCompaction()) {
                lastCompaction = entries.get(i);
                break;
            }
        }

        int total = 0;
        boolean found = false;

        for (SessionEntry entry : entries) {
            // 如果有 compaction，从 firstKeptEntryId 开始计算
            if (lastCompaction != null) {
                if (entry.getId().equals(lastCompaction.getFirstKeptEntryId())) {
                    found = true;
                }
                if (found && entry.isMessage()) {
                    String content = entry.getContent() != null ? entry.getContent() : "";
                    total += compactionChecker.estimatePromptTokens(content);
                }
            } else {
                // 没有 compaction，计算所有 message
                if (entry.isMessage()) {
                    String content = entry.getContent() != null ? entry.getContent() : "";
                    total += compactionChecker.estimatePromptTokens(content);
                }
            }
        }

        return total;
    }

    // ==================== 子服务访问 ====================

    /**
     * 【选择压缩边界】
     *
     * 在用户消息边界处选择切割点，确保压缩后保留的消息从完整的用户消息开始。
     *
     * 【算法】：
     * 1. 从 start 开始遍历消息
     * 2. 累加每个消息的 Token 数到 removedTokens
     * 3. 遇到用户消息（idx > start 且 role=user）时记录边界
     * 4. 当 removedTokens >= tokensToRemove 时返回当前边界
     * 5. 如果遍历完仍未达到目标，返回最后一个记录的边界（如有）
     *
     * 【返回值】：int[2] = {endIdx, removedTokens}
     * - endIdx: 压缩边界，消息 [0, endIdx) 将被压缩
     * - removedTokens: 预计移除的 Token 数
     *
     * @param messages 消息列表
     * @param start 起始索引（通常为 0）
     * @param tokensToRemove 需要移除的 Token 数
     * @return 边界信息 [endIdx, removedTokens]，找不到则返回 empty
     */
    public Optional<int[]> pickConsolidationBoundary(
            List<Map<String, Object>> messages,
            int start,
            int tokensToRemove) {
        /** Pick a user-turn boundary that removes enough old prompt tokens. */
        if (start >= messages.size() || tokensToRemove <= 0) {
            return Optional.empty();
        }

        int removedTokens = 0;
        int[] lastBoundary = null;

        for (int idx = start; idx < messages.size(); idx++) {
            Map<String, Object> message = messages.get(idx);
            // 在起始点之后，遇到用户消息时作为潜在的边界
            if (idx > start && "user".equals(message.get("role"))) {
                lastBoundary = new int[]{idx, removedTokens};
                // 如果已移除的令牌数足够，返回当前边界
                if (removedTokens >= tokensToRemove) {
                    return Optional.of(lastBoundary);
                }
            }
            // 累加当前消息的令牌数
            removedTokens += estimateMessageTokens(message);
        }

        return lastBoundary != null ? Optional.of(lastBoundary) : Optional.empty();
    }

    /**
     * 【获取 CompactionChecker 子服务】
     *
     * 用于外部访问 Token 估算和预算计算逻辑。
     *
     * @return CompactionChecker 实例
     */
    public CompactionChecker getCompactionChecker() {
        return compactionChecker;
    }

    /**
     * 【基于 Token 预算执行压缩】
     *
     * 【入口方法】由 MemoryManager 调用，检查 Token 使用并执行压缩。
     *
     * 【处理流程】：
     * 1. 检查是否已有压缩在进行中（避免并发冲突）
     * 2. 从 JSONL 读取当前消息列表（自动应用之前的压缩）
     * 3. 估算 Token 数，判断是否超过安全预算
     * 4. 如需要，调用 executeCompactionRounds 执行多轮压缩
     *
     * 【预算计算】：
     * - 安全预算 = contextWindow - reserveTokens - safetyMargin
     * - 压缩目标 = 安全预算 * 0.8（预留 20% 空间）
     *
     * @param session 要压缩的会话
     * @param jsonlStore JSONL 会话存储
     */
    public void maybeConsolidateByTokens(Session session, JsonlSessionStore jsonlStore) {
        if (contextWindowTokens <= 0) {
            return;
        }

        String sessionKey = session.getKey();

        // 【检查】如果该会话正在压缩中，跳过（防止后台压缩与前台请求冲突）
        if (compactingSessions.getOrDefault(sessionKey, false)) {
            log.debug("[Consolidator] Session {} is already compacting, skip", sessionKey);
            return;
        }

        ReentrantLock lock = getLock(sessionKey);
        lock.lock();
        try {
            // 【标记为压缩中】
            compactingSessions.put(sessionKey, true);

            // 【从 SessionManager 获取原始 entries】用于压缩决策
            List<SessionEntry> allEntries = sessions.getAllEntries(sessionKey);

            if (allEntries.isEmpty()) {
                log.debug("[Consolidator] 没有需要压缩的消息: {}", sessionKey);
                return;
            }

            // 【构建消息列表用于 Token 估算】只包含 MESSAGE 类型
            List<Map<String, Object>> messages = new ArrayList<>();
            for (SessionEntry entry : allEntries) {
                if (entry.isMessage()) {
                    messages.add(entry.toMessageMap());
                }
            }

            // 【Token 估算】使用启发式估算当前消息列表
            int estimatedTokens = compactionChecker.estimateMessagesTokens(messages);

            // 计算安全预算和压缩目标
            int safeBudget = compactionChecker.calculateSafeBudget(contextWindowTokens);
            int target = compactionChecker.calculateCompactionTarget(safeBudget);

            log.info("[Consolidator] Token 估算: session={}, tokens={}/{}, safeBudget={}, target={}",
                    sessionKey, estimatedTokens, contextWindowTokens, safeBudget, target);

            // 如果当前 Token 数在预算内，无需压缩
            if (estimatedTokens < safeBudget) {
                log.debug("[Consolidator] Token 在预算内，无需压缩: {}/{}" , estimatedTokens, safeBudget);
                return;
            }

            log.info("[Consolidator] 开始执行会话压缩: session={}, tokens={} >= safeBudget={}",
                    sessionKey, estimatedTokens, safeBudget);

            // 【步骤 3】执行多轮压缩
            executeCompactionRounds(session, jsonlStore, allEntries, estimatedTokens, target);

        } finally {
            // 【清除压缩标志】
            compactingSessions.remove(sessionKey);
            // 确保释放锁
            lock.unlock();
        }
    }

    /**
     * 【执行多轮压缩】
     *
     * 循环执行压缩直到 Token 数降至目标以下，或达到最大轮次。
     *
     * 【每轮处理】：
     * 1. 从 allEntries 构建消息列表（排除之前的 compaction）
     * 2. 选择压缩边界（pickConsolidationBoundary）
     * 3. 获取 firstKeptEntryId = entries[endIdx] 的 ID
     * 4. 调用 consolidateMessages 生成摘要并写入 JSONL
     * 5. 重新加载 entries，继续下一轮
     *
     * @param session 当前会话
     * @param jsonlStore JSONL 会话存储
     * @param allEntries 当前所有 entries（会被更新）
     * @param initialTokens 初始 Token 数
     * @param target 目标 Token 数
     */
    private void executeCompactionRounds(Session session, JsonlSessionStore jsonlStore,
                                         List<SessionEntry> allEntries,
                                         int initialTokens, int target) {
        String sessionKey = session.getKey();
        int estimatedTokens = initialTokens;
        int maxRounds = agentConfig.getMaxConsolidationRounds();

        for (int roundNum = 0; roundNum < maxRounds; roundNum++) {
            // 从 entries 构建消息列表（只包含 MESSAGE 类型）
            List<Map<String, Object>> messages = new ArrayList<>();
            for (SessionEntry entry : allEntries) {
                if (entry.isMessage()) {
                    messages.add(entry.toMessageMap());
                }
            }

            // 如果已达到目标，停止压缩
            if (estimatedTokens <= target) {
                log.info("[Consolidator] 已达到压缩目标: {} <= {}", estimatedTokens, target);
                return;
            }

            // 计算需要移除的 Token 数
            int tokensToRemove = Math.max(1, estimatedTokens - target);

            // 选择压缩边界
            Optional<int[]> boundary = pickConsolidationBoundary(messages, 0, tokensToRemove);
            if (!boundary.isPresent()) {
                log.debug("[Consolidator] 无法找到安全的压缩边界: {} (round {})", sessionKey, roundNum);
                return;
            }

            int endIdx = boundary.get()[0];

            // 获取要归档的消息块
            List<Map<String, Object>> chunk = messages.subList(0, endIdx);

            if (chunk.isEmpty()) {
                log.debug("[Consolidator] 压缩块为空，停止压缩: {}", sessionKey);
                return;
            }

            log.info("[Consolidator] 压缩轮次 {}: session={}, tokens={}/{}, chunk={} msgs",
                    roundNum, sessionKey, estimatedTokens, contextWindowTokens, chunk.size());

            // 【关键】获取边界后第一个保留的 entry 的 ID（从原始 entries 中）
            String firstKeptEntryId = getEntryIdAtIndex(allEntries, endIdx);
            // 计算 threshold（用于控制摘要输出长度）
            int threshold = compactionChecker.calculateThreshold(contextWindowTokens);
            CompletableFuture<Boolean> result = consolidateMessages(chunk, firstKeptEntryId, sessionKey, threshold);
            if (!result.join()) {
                log.error("[Consolidator] 压缩失败: {}", sessionKey);
                return;
            }

            // 更新会话
            sessions.save(session);

            // 【关键】重新加载 entries（包含新添加的 compaction）
            sessions.reloadEntries(sessionKey);
            allEntries = sessions.getAllEntries(sessionKey);

            // 重新估算 Token 数
            estimatedTokens = estimateEntriesTokens(allEntries);

            log.info("[Consolidator] 压缩完成: session={}, newTokens={}", sessionKey, estimatedTokens);
        }

        log.info("[Consolidator] 达到最大压缩轮次: {}", sessionKey);
    }

    // ==================== Token 估算方法 ====================

    /**
     * 每条消息的基础 Token 开销（角色标记、格式等）
     */
    private static final int BASE_MESSAGE_TOKENS = 4;

    /**
     * 每个字符的平均 Token 数（粗略估算）
     */
    private static final double CHARS_PER_TOKEN = 3.0;

    /**
     * 【单条消息 Token 估算】
     *
     * 简化版估算，用于 pickConsolidationBoundary 快速计算。
     * 公式：BASE_MESSAGE_TOKENS + ceil(content.length / CHARS_PER_TOKEN)
     *
     * 【注意】：完整估算逻辑在 CompactionChecker 中实现，包含：
     * - CJK 字符特殊处理
     * - 角色标记开销
     * - 工具调用开销
     *
     * @param message 要估算的消息
     * @return 估算的 Token 数
     */
    private int estimateMessageTokens(Map<String, Object> message) {
        if (message == null) {
            return 0;
        }

        int tokens = BASE_MESSAGE_TOKENS;

        // 估算内容文本 Token 数
        Object content = message.get("content");
        if (content instanceof String) {
            String text = (String) content;
            tokens += (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
        }

        return tokens;
    }
}
