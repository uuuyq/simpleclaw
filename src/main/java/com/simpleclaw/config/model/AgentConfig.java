package com.simpleclaw.config.model;

import com.simpleclaw.config.Config;

import java.util.List;
import java.util.Map;

/**
 * 【Agent 配置类】
 * 
 * 封装 Agent 运行所需的所有配置参数，简化 AgentLoop 的构造函数。
 * 从 Config 对象中提取 Agent 相关配置，便于传递和使用。
 */
public class AgentConfig {
    
    // ===== 模型配置 =====
    private String model;
    private int maxTokens;
    private int contextWindow;
    private double temperature;
    private int maxToolIterations;
    private int memoryWindow;
    
    // ===== 工具配置 =====
    private String webSearchApiKey;
    private ExecToolConfig execConfig;
    private boolean restrictToWorkspace;
    private Map<String, MCPServerConfig> mcpServers;
    
    // ===== 工作空间 =====
    private String workspacePath;

    // ===== Provider 配置（用于向量记忆）=====
    private Map<String, ProviderConfig> providers;

    // ===== 记忆检索配置 =====
    private MemoryConfig memoryConfig;

    // ===== 上下文预算配置 =====
    private ContextBudgetConfig contextBudgetConfig;

    // ===== 压缩质量审核配置 =====
    private QualityGuardConfig qualityGuardConfig;

    // ===== 技能系统配置 =====
    private SkillsConfig skillsConfig;

    /**
     * 【从主配置创建 AgentConfig】
     * 
     * @param config 主配置对象
     * @return AgentConfig 实例
     */
    public static AgentConfig fromConfig(Config config) {
        AgentConfig agentConfig = new AgentConfig();
        AgentsConfig agents = config.getAgents();
        ToolsConfig tools = config.getTools();
        
        // 模型配置
        agentConfig.model = agents.getModel();
        agentConfig.maxTokens = agents.getMaxTokens();
        agentConfig.contextWindow = agents.getContextWindow();
        agentConfig.temperature = agents.getTemperature();
        agentConfig.maxToolIterations = agents.getMaxToolIterations();
        agentConfig.memoryWindow = agents.getMemoryWindow();
        
        // 工具配置
        agentConfig.webSearchApiKey = tools.getWebSearchApiKey();
        agentConfig.execConfig = tools.getExec();
        agentConfig.restrictToWorkspace = tools.isRestrictToWorkspace();
        agentConfig.mcpServers = tools.getMcpServers();
        
        // 工作空间（Path 转为 String）
        agentConfig.workspacePath = config.getWorkspacePath() != null ? config.getWorkspacePath().toString() : null;

        // Provider 配置（用于向量记忆）
        agentConfig.providers = config.getProviders();

        // 记忆检索配置
        agentConfig.memoryConfig = agents.getMemory();

        // 上下文预算配置
        agentConfig.contextBudgetConfig = agents.getContextBudget();

        // 压缩质量审核配置
        agentConfig.qualityGuardConfig = agents.getQualityGuard();

        // 技能系统配置
        agentConfig.skillsConfig = agents.getSkills();

        return agentConfig;
    }
    
    // ===== Getters =====
    
    public String getModel() { return model; }
    public int getMaxTokens() { return maxTokens; }
    public int getContextWindow() { return contextWindow; }
    public double getTemperature() { return temperature; }
    public int getMaxToolIterations() { return maxToolIterations; }
    public int getMemoryWindow() { return memoryWindow; }
    public String getWebSearchApiKey() { return webSearchApiKey; }
    public ExecToolConfig getExecConfig() { return execConfig; }
    public boolean isRestrictToWorkspace() { return restrictToWorkspace; }
    public Map<String, MCPServerConfig> getMcpServers() { return mcpServers; }
    public String getWorkspacePath() { return workspacePath; }
    public Map<String, ProviderConfig> getProviders() { return providers; }

    // ===== 记忆检索配置 getter =====
    public MemoryConfig getMemoryConfig() { return memoryConfig; }

    // ===== 上下文预算配置 getter =====
    public ContextBudgetConfig getContextBudgetConfig() { return contextBudgetConfig; }

    // ===== 压缩质量审核配置 getter =====
    public QualityGuardConfig getQualityGuardConfig() { return qualityGuardConfig; }

    // ===== 便捷方法：从配置对象中获取具体值 =====

    public float getVectorWeight() {
        return memoryConfig != null ? memoryConfig.getVectorWeight() : 0.7f;
    }

    public float getTextWeight() {
        return memoryConfig != null ? memoryConfig.getTextWeight() : 0.3f;
    }

    public int getCandidateMultiplier() {
        return memoryConfig != null ? memoryConfig.getCandidateMultiplier() : 4;
    }

    public int getMaxInjectedChars() {
        return memoryConfig != null ? memoryConfig.getMaxInjectedChars() : 4000;
    }

    public int getReserveTokens() {
        return contextBudgetConfig != null ? contextBudgetConfig.getReserveTokens() : 8192;
    }

    public int getSafetyMargin() {
        return contextBudgetConfig != null ? contextBudgetConfig.getSafetyMargin() : 2048;
    }

    public int getMaxConsolidationRounds() {
        return contextBudgetConfig != null ? contextBudgetConfig.getMaxConsolidationRounds() : 5;
    }

    public String getEstimationMode() {
        return contextBudgetConfig != null ? contextBudgetConfig.getEstimationMode() : "heuristic";
    }

    public int getReserveTokensFloor() {
        return contextBudgetConfig != null ? contextBudgetConfig.getReserveTokensFloor() : 20000;
    }

    public int getSoftThresholdTokens() {
        return contextBudgetConfig != null ? contextBudgetConfig.getSoftThresholdTokens() : 4000;
    }

    public int getQualityGuardMaxRetries() {
        return qualityGuardConfig != null ? qualityGuardConfig.getMaxRetries() : 1;
    }

    public boolean isQualityGuardEnabled() {
        return qualityGuardConfig != null ? qualityGuardConfig.isEnabled() : true;
    }

    public String getIdentifierPolicy() {
        return qualityGuardConfig != null ? qualityGuardConfig.getIdentifierPolicy() : "strict";
    }

    public int getRecentTurnsPreserve() {
        return qualityGuardConfig != null ? qualityGuardConfig.getRecentTurnsPreserve() : 3;
    }

    /**
     * 【获取压缩输出因子】
     * 控制压缩摘要的最大长度：threshold * factor
     * 默认 0.5，即摘要不超过阈值的一半
     */
    public double getCompactionOutputFactor() {
        return contextBudgetConfig != null ? contextBudgetConfig.getCompactionOutputFactor() : 0.5;
    }

    // ===== 技能系统配置 getter =====
    public SkillsConfig getSkillsConfig() {
        return skillsConfig != null ? skillsConfig : new SkillsConfig();
    }
}
