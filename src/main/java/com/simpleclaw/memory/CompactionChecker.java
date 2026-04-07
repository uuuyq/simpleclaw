package com.simpleclaw.memory;

import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.session.SessionManager;
import com.simpleclaw.session.model.SessionEntry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 【会话压缩判断器】
 *
 * 负责 Token 估算和压缩判断。
 *
 * 【Token 计算两层架构】：
 * 1. 第一层：从 SessionManager 内存读取（最优路径）
 *    - 从后向前找到第一个非 user 类型的 entry
 *    - 读取其 promptTokens（已包含 prompt + completion）
 * 2. 第二层：启发式估算（兜底）
 *    - 启发式估算内存中所有消息的 Token 数
 *
 * 【预估公式】：
 * projectedTokens = lastPromptTokens + newMessageEstimate
 */
@Slf4j
public class CompactionChecker {

    // ========== Token 估算常量 ==========

    /** CJK 字符膨胀系数（CJK 字符权重更高） */
    private static final double CJK_EXPANSION_FACTOR = 1.5;

    /** 非 CJK 字符：每 4 字符约 1 token */
    private static final double CHARS_PER_TOKEN = 4.0;

    // ========== 依赖组件 ==========

    private final AgentConfig agentConfig;

    /**
     * 【构造函数】
     *
     * @param agentConfig Agent 配置（包含预算参数）
     */
    public CompactionChecker(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    // ========== Token 估算方法 ==========

    /**
     * 【启发式估算消息列表 Token 数】
     */
    public int estimateMessagesTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            String content = (String) msg.getOrDefault("content", "");
            total += estimatePromptTokens(content);
        }
        return total;
    }

    /**
     * 【启发式估算单条消息 Token 数】
     *
     * 规则：
     * - CJK 字符：约 1.5 tokens/字符（直接计算，不再除 4）
     * - 非 CJK 字符：约 4 字符/token
     *
     * @param content 消息内容
     * @return 估算的 Token 数
     */
    public int estimatePromptTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        int cjkCount = 0;
        int nonCjkCount = 0;

        for (char c : content.toCharArray()) {
            if (isCjkCharacter(c)) {
                cjkCount++;
            } else {
                nonCjkCount++;
            }
        }

        // CJK 字符直接按 1.5 tokens/字符计算，非 CJK 按 4 字符/token 计算
        double cjkTokens = cjkCount * CJK_EXPANSION_FACTOR;
        double nonCjkTokens = nonCjkCount / CHARS_PER_TOKEN;
        return (int) Math.ceil(cjkTokens + nonCjkTokens);
    }


    /**
     * 【判断是否为 CJK 字符】
     */
    private boolean isCjkCharacter(char c) {
        // CJK 统一表意文字范围
        return (c >= '\u4E00' && c <= '\u9FFF') ||   // 基本区
               (c >= '\u3400' && c <= '\u4DBF') ||   // 扩展 A 区
               (c >= '\uF900' && c <= '\uFAFF');     // 兼容区
    }

    /**
     * 【两层 Token 计算：计算预估 Token 数】
     *
     * 【核心公式】：
     * projectedTokens = lastPromptTokens + newMessageEstimate
     *
     * 【两层架构】：
     * 第一层：从 SessionManager 内存读取（最优路径，0 I/O）
     *        - 从后向前找到第一个非 user 类型的 entry
     *        - 读取其 promptTokens（已包含 prompt + completion）
     *
     * 第二层：启发式估算（兜底路径）
     *        - 启发式估算内存中所有消息的 Token 数
     *
     * @param messages 内存中的消息列表（来自 ContextBuilder）
     * @param sessionManager 会话管理器（用于从内存读取 entries）
     * @param sessionKey 会话键
     * @param newMessageContent 新消息内容（用于估算）
     * @return 预估的 Token 总数
     */
    public int calculateProjectedTokens(List<Map<String, Object>> messages,
                                        SessionManager sessionManager,
                                        String sessionKey,
                                        String newMessageContent) {
        // 【步骤 0】估算新消息
        int newMessageEstimate = estimatePromptTokens(newMessageContent);

        // 【第一层】从 SessionManager 内存读取最后一个非 user entry 的 totalTokens
        List<SessionEntry> allEntries = sessionManager.getAllEntries(sessionKey);

        // 从后向前找最后一个非 user 且有 totalTokens 的 entry（compaction 或 assistant）
        for (int i = allEntries.size() - 1; i >= 0; i--) {
            SessionEntry entry = allEntries.get(i);
            // 跳过 user 类型的 entry
            if (entry.isMessage() && "user".equals(entry.getRole())) {
                continue;
            }
            // 找到非 user 类型且有 totalTokens 的 entry（compaction 或 assistant）
            if (entry.getTotalTokens() != null && entry.getTotalTokens() > 0) {
                return entry.getTotalTokens() + newMessageEstimate;
            }
        }

        // 【第二层】启发式估算（兜底）
        int historyTokens = estimateMessagesTokens(messages);
        int projectedTokens = historyTokens + newMessageEstimate;
        return projectedTokens;
    }

    // ========== 阈值计算 ==========

    /**
     * 【计算压缩阈值】
     * threshold = contextWindowTokens - reserveTokensFloor - softThresholdTokens
     */
    public int calculateThreshold(int contextWindowTokens) {
        int threshold = contextWindowTokens
                - agentConfig.getReserveTokensFloor()
                - agentConfig.getSoftThresholdTokens();
        return Math.max(threshold, contextWindowTokens / 2);
    }

    /**
     * 【计算安全预算】
     */
    public int calculateSafeBudget(int contextWindowTokens) {
        int safeBudget = contextWindowTokens
                - agentConfig.getReserveTokensFloor()
                - agentConfig.getSoftThresholdTokens();
        return Math.max(safeBudget, (int) (contextWindowTokens * 0.3));
    }

    /**
     * 【计算压缩目标】
     */
    public int calculateCompactionTarget(int safeBudget) {
        return safeBudget / 2;
    }

    // ========== 压缩判断 ==========

    /**
     * 【压缩需求判断结果】
     */
    public static class CompactionCheckResult {
        private final boolean needed;
        private final int projectedTokens;
        private final int threshold;
        private final int contextWindowTokens;
        private final double usagePercent;
        private final int marginToThreshold;

        public CompactionCheckResult(boolean needed, int projectedTokens, int threshold,
                                     int contextWindowTokens, double usagePercent, int marginToThreshold) {
            this.needed = needed;
            this.projectedTokens = projectedTokens;
            this.threshold = threshold;
            this.contextWindowTokens = contextWindowTokens;
            this.usagePercent = usagePercent;
            this.marginToThreshold = marginToThreshold;
        }

        public boolean isNeeded() { return needed; }
        public int getProjectedTokens() { return projectedTokens; }
        public int getThreshold() { return threshold; }
        public int getContextWindowTokens() { return contextWindowTokens; }
        public double getUsagePercent() { return usagePercent; }
        public int getMarginToThreshold() { return marginToThreshold; }
    }

    /**
     * 【判断是否需要进行压缩】
     */
    public CompactionCheckResult checkCompactionNeeded(int projectedTokens, int contextWindowTokens) {
        int threshold = calculateThreshold(contextWindowTokens);
        boolean needed = projectedTokens >= threshold;
        double usagePercent = (projectedTokens * 100.0) / contextWindowTokens;
        int marginToThreshold = threshold - projectedTokens;

        log.info("[CompactionChecker] 压缩判断: projected={}, threshold={}, needed={}",
                projectedTokens, threshold, needed);

        return new CompactionCheckResult(needed, projectedTokens, threshold,
                contextWindowTokens, usagePercent, marginToThreshold);
    }
}
