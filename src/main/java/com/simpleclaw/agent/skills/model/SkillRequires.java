package com.simpleclaw.agent.skills.model;

import java.util.Collections;
import java.util.List;

/**
 * 【技能运行时依赖要求】
 *
 * 对应 metadata.openclaw.requires 字段，用于运行时环境检测。
 */
public class SkillRequires {

    /** 所有 bin 都必须存在（AND 逻辑） */
    private List<String> bins;

    /** 任一 bin 存在即可（OR 逻辑） */
    private List<String> anyBins;

    /** 所有 env 变量都必须设置 */
    private List<String> env;

    /** 配置路径条件，如 "browser.enabled" */
    private List<String> config;

    public SkillRequires() {
        this.bins = Collections.emptyList();
        this.anyBins = Collections.emptyList();
        this.env = Collections.emptyList();
        this.config = Collections.emptyList();
    }

    // ==================== Getters & Setters ====================

    public List<String> getBins() {
        return bins != null ? bins : Collections.emptyList();
    }

    public void setBins(List<String> bins) {
        this.bins = bins;
    }

    public List<String> getAnyBins() {
        return anyBins != null ? anyBins : Collections.emptyList();
    }

    public void setAnyBins(List<String> anyBins) {
        this.anyBins = anyBins;
    }

    public List<String> getEnv() {
        return env != null ? env : Collections.emptyList();
    }

    public void setEnv(List<String> env) {
        this.env = env;
    }

    public List<String> getConfig() {
        return config != null ? config : Collections.emptyList();
    }

    public void setConfig(List<String> config) {
        this.config = config;
    }

    /**
     * 【检查是否有任何依赖要求】
     */
    public boolean hasRequirements() {
        return !getBins().isEmpty()
                || !getAnyBins().isEmpty()
                || !getEnv().isEmpty()
                || !getConfig().isEmpty();
    }

    @Override
    public String toString() {
        return "SkillRequires{" +
                "bins=" + bins +
                ", anyBins=" + anyBins +
                ", env=" + env +
                ", config=" + config +
                '}';
    }
}
