package com.simpleclaw.config.model;

/**
 * 【上下文预算配置】
 *
 * 用于控制 Agent 在处理消息时的 Token 分配策略。
 */
public class ContextBudgetConfig {
    private int reserveTokens = 8192;
    private int safetyMargin = 2048;
    private int maxConsolidationRounds = 5;
    private String estimationMode = "heuristic"; // "tiktoken" or "heuristic"

    // Token 阈值参数（参考 OpenClaw）
    private int reserveTokensFloor = 20000;  // 预留思考空间
    private int softThresholdTokens = 4000;  // 软阈值

    public int getReserveTokens() { return reserveTokens; }
    public void setReserveTokens(int reserveTokens) { this.reserveTokens = reserveTokens; }

    public int getSafetyMargin() { return safetyMargin; }
    public void setSafetyMargin(int safetyMargin) { this.safetyMargin = safetyMargin; }

    public int getMaxConsolidationRounds() { return maxConsolidationRounds; }
    public void setMaxConsolidationRounds(int maxConsolidationRounds) { this.maxConsolidationRounds = maxConsolidationRounds; }

    public String getEstimationMode() { return estimationMode; }
    public void setEstimationMode(String estimationMode) { this.estimationMode = estimationMode; }

    public int getReserveTokensFloor() { return reserveTokensFloor; }
    public void setReserveTokensFloor(int reserveTokensFloor) { this.reserveTokensFloor = reserveTokensFloor; }

    public int getSoftThresholdTokens() { return softThresholdTokens; }
    public void setSoftThresholdTokens(int softThresholdTokens) { this.softThresholdTokens = softThresholdTokens; }
}
