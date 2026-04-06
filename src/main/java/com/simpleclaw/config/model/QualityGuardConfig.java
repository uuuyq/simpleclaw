package com.simpleclaw.config.model;

/**
 * 【压缩质量审核配置】
 *
 * 控制会话压缩时的质量审核行为。
 */
public class QualityGuardConfig {

    /** 质量审核最大重试次数 */
    private int maxRetries = 1;

    /** 是否启用质量审核 */
    private boolean enabled = true;

    /** 标识符保全策略："strict" 或 "off" */
    private String identifierPolicy = "strict";

    /** 压缩时保留的最近对话轮数 */
    private int recentTurnsPreserve = 3;

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIdentifierPolicy() {
        return identifierPolicy;
    }

    public void setIdentifierPolicy(String identifierPolicy) {
        this.identifierPolicy = identifierPolicy;
    }

    public int getRecentTurnsPreserve() {
        return recentTurnsPreserve;
    }

    public void setRecentTurnsPreserve(int recentTurnsPreserve) {
        this.recentTurnsPreserve = recentTurnsPreserve;
    }
}
