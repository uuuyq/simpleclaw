package com.simpleclaw.memory;

import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.session.JsonlSessionStore;
import com.simpleclaw.session.model.SessionEntry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 【会话压缩判断器】
 *
 * 负责判断当前会话是否需要进行压缩。
 *
 * 【Token 计算三层递降策略】（参考 OpenClaw）：
 * 1. 第一层：直接读缓存真实值（API 返回的 usage）
 * 2. 第二层：扫描 JSONL 尾部提取真实值（反向读取 64KB 块）
 * 3. 第三层：启发式估算新消息补丁（chars / 4）
 *
 * 【预估公式】：
 * estimatedTokens = lastPromptTokens + lastOutputTokens + newMessageEstimate
 *
 * 【阈值公式】：
 * threshold = contextWindowTokens - reserveTokensFloor - softThresholdTokens
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

    // ========== 第一层：直接读缓存真实值 ==========

    // 注意：当前实现中，真实 token 值由调用者在 LLM 响应后通过其他方式存储
    // 这里预留接口，未来可以实现缓存机制

    // ========== 第二层：扫描 JSONL 尾部提取真实值 ==========

    /**
     * 【从 JSONL 尾部读取最后一条非零 usage】
     *
     * 反向扫描 JSONL 文件，找到最后一条包含非零 usage 的 entry
     *
     * @param jsonlStore JSONL 会话存储
     * @return [promptTokens, completionTokens]，如果没有找到则返回 [-1, -1]
     */
    public int[] readLastNonzeroUsageFromSessionLog(JsonlSessionStore jsonlStore) {
        List<SessionEntry> entries = jsonlStore.readAllEntries();

        // 从后向前扫描
        for (int i = entries.size() - 1; i >= 0; i--) {
            SessionEntry entry = entries.get(i);
            if (entry.getUsage() != null && entry.getUsage().getTotalTokens() != null
                    && entry.getUsage().getTotalTokens() > 0) {
                int promptTokens = entry.getUsage().getPromptTokens() != null
                        ? entry.getUsage().getPromptTokens() : 0;
                int completionTokens = entry.getUsage().getCompletionTokens() != null
                        ? entry.getUsage().getCompletionTokens() : 0;
                return new int[]{promptTokens, completionTokens};
            }
        }

        return new int[]{-1, -1}; // 未找到
    }

    // ========== 第三层：启发式估算 ==========

    /**
     * 【启发式估算消息 Token 数】
     *
     * 规则：chars / 4，CJK 字符先做膨胀修正
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

        // CJK 字符膨胀后计算
        double adjustedChars = cjkCount * CJK_EXPANSION_FACTOR + nonCjkCount;
        return (int) Math.ceil(adjustedChars / CHARS_PER_TOKEN);
    }

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
     * 【判断是否为 CJK 字符】
     */
    private boolean isCjkCharacter(char c) {
        // CJK 统一表意文字范围
        return (c >= '\u4E00' && c <= '\u9FFF') ||   // 基本区
               (c >= '\u3400' && c <= '\u4DBF') ||   // 扩展 A 区
               (c >= '\uF900' && c <= '\uFAFF');     // 兼容区
    }

    // ========== Token 预估与阈值判断 ==========

    /**
     * 【计算预估 Token 数】
     *
     * 【预估公式】：
     * estimatedTokens = historyTokens + newMessageEstimate
     *
     * 【计算方式】：
     * 1. 使用 buildContext() 获取当前会话历史（已排除压缩部分）
     * 2. 启发式估算历史消息的 Token 数
     * 3. 加上新消息的估算
     *
     * 【注意】：不使用 usage 字段，因为 usage 只反映某次请求的 Token 使用量，
     * 不能代表当前累积的上下文大小。
     *
     * @param jsonlStore JSONL 会话存储
     * @param newMessageContent 新消息内容（用于估算）
     * @return 预估的 Token 总数
     */
    public int calculateProjectedTokens(JsonlSessionStore jsonlStore, String newMessageContent) {
        // 【步骤 1】获取当前会话历史（buildContext 自动处理 compaction）
        List<Map<String, Object>> messages = jsonlStore.buildContext();

        // 【步骤 2】估算历史消息的 Token 数
        int historyTokens = estimateMessagesTokens(messages);

        // 【步骤 3】估算新消息
        int newMessageEstimate = estimatePromptTokens(newMessageContent);

        // Token 预估计算
        int projectedTokens = historyTokens + newMessageEstimate;

        log.info("[CompactionChecker] Token 预估: messages={}, history={}, newEstimate={}, total={}",
                messages.size(), historyTokens, newMessageEstimate, projectedTokens);

        return projectedTokens;
    }

    /**
     * 【计算压缩阈值】
     *
     * 【阈值公式】：
     * threshold = contextWindowTokens - reserveTokensFloor - softThresholdTokens
     *
     * 注意：对于小上下文窗口模型，阈值不会低于上下文窗口的 50%
     *
     * @param contextWindowTokens 模型上下文窗口大小
     * @return 压缩阈值
     */
    public int calculateThreshold(int contextWindowTokens) {
        int threshold = contextWindowTokens
                - agentConfig.getReserveTokensFloor()
                - agentConfig.getSoftThresholdTokens();
        // 确保阈值不会太低（至少为上下文窗口的 50%）
        return Math.max(threshold, contextWindowTokens / 2);
    }

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
     *
     * @param projectedTokens 预估的 Token 数
     * @param contextWindowTokens 模型上下文窗口大小
     * @return 包含判断结果和详细信息的 CompactionCheckResult
     */
    public CompactionCheckResult checkCompactionNeeded(int projectedTokens, int contextWindowTokens) {
        int threshold = calculateThreshold(contextWindowTokens);
        boolean needed = projectedTokens >= threshold;
        double usagePercent = (projectedTokens * 100.0) / contextWindowTokens;
        int marginToThreshold = threshold - projectedTokens;

        log.debug("[CompactionChecker] 压缩判断: projected={}, threshold={}, needed={}",
                projectedTokens, threshold, needed);

        return new CompactionCheckResult(needed, projectedTokens, threshold,
                contextWindowTokens, usagePercent, marginToThreshold);
    }

    /**
     * 【判断是否需要进行压缩】（简化版，只返回 boolean）
     *
     * @param projectedTokens 预估的 Token 数
     * @param contextWindowTokens 模型上下文窗口大小
     * @return true 如果需要压缩
     */
    public boolean isCompactionNeeded(int projectedTokens, int contextWindowTokens) {
        return checkCompactionNeeded(projectedTokens, contextWindowTokens).isNeeded();
    }

    /**
     * 【计算安全预算】
     *
     * 安全预算 = 模型最大上下文 - 预留思考空间 - 安全余量
     *
     * 注意：对于小上下文窗口模型，安全预算不会低于上下文窗口的 30%
     *
     * @param contextWindowTokens 模型上下文窗口大小
     * @return 安全预算（Token 数）
     */
    public int calculateSafeBudget(int contextWindowTokens) {
        int safeBudget = contextWindowTokens
                - agentConfig.getReserveTokensFloor()
                - agentConfig.getSoftThresholdTokens();
        // 确保安全预算不会太低（至少为上下文窗口的 30%）
        return Math.max(safeBudget, (int) (contextWindowTokens * 0.3));
    }

    /**
     * 【计算压缩目标】
     *
     * 压缩目标 = 安全预算 / 2
     * 目的是将 Token 使用量降到预算的一半，为后续对话留出空间
     *
     * @param safeBudget 安全预算
     * @return 压缩目标（Token 数）
     */
    public int calculateCompactionTarget(int safeBudget) {
        return safeBudget / 2;
    }
}
