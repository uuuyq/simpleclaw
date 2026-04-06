package com.simpleclaw.config.model;

import java.util.Collections;
import java.util.List;

/**
 * Agent 默认配置：工作区、模型、Token 上限、温度、工具迭代次数、记忆窗口等。
 * 对应 config.json 中 agents 节点，键名 camelCase。
 */
public class AgentsConfig {

    /** 工作区路径（可为相对或 ~/.simpleclaw/workspace） */
    private String workspace;
    /** 默认模型名 */
    private String model;
    /** 单次回复最大 token 数 */
    private int maxTokens = 4096;
    /** 采样温度 */
    private double temperature = 0.7;
    /** 单轮最大工具调用迭代次数 */
    private int maxToolIterations = 10;
    /** 会话记忆条数超过此值触发 consolidate */
    private int memoryWindow = 20;
    /** 默认使用的 provider 名（用于从 providers 中选取） */
    private String defaultProvider;
    /** 可选：指定 model 使用的 provider，未设则用 defaultProvider */
    private List<String> providerModels;
    /** 记忆检索配置 */
    private MemoryConfig memory;
    /** 上下文预算配置 */
    private ContextBudgetConfig contextBudget;
    /** 压缩质量审核配置 */
    private QualityGuardConfig qualityGuard;
    /** 技能系统配置 */
    private SkillsConfig skills;

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = maxToolIterations;
    }

    public int getMemoryWindow() {
        return memoryWindow;
    }

    public void setMemoryWindow(int memoryWindow) {
        this.memoryWindow = memoryWindow;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public List<String> getProviderModels() {
        return providerModels == null ? Collections.<String>emptyList() : providerModels;
    }

    public void setProviderModels(List<String> providerModels) {
        this.providerModels = providerModels;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }

    public ContextBudgetConfig getContextBudget() {
        return contextBudget;
    }

    public void setContextBudget(ContextBudgetConfig contextBudget) {
        this.contextBudget = contextBudget;
    }

    public QualityGuardConfig getQualityGuard() {
        return qualityGuard;
    }

    public void setQualityGuard(QualityGuardConfig qualityGuard) {
        this.qualityGuard = qualityGuard;
    }

    public SkillsConfig getSkills() {
        return skills != null ? skills : new SkillsConfig();
    }

    public void setSkills(SkillsConfig skills) {
        this.skills = skills;
    }
}
