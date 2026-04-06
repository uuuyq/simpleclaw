package com.simpleclaw.providers;

import com.simpleclaw.config.Config;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * 【Embedding 提供商工厂】
 *
 * 负责创建 EmbeddingProvider 实例。
 * 支持从 AgentConfig 或 Config 创建。
 * 自动识别提供商类型（OpenAI 或 Qwen）。
 */
@Slf4j
public final class EmbeddingProviderFactory {

    private EmbeddingProviderFactory() {
        // 工具类，禁止实例化
    }

    /**
     * 【从 AgentConfig 创建 EmbeddingProvider】
     *
     * @param agentConfig Agent 配置
     * @return EmbeddingProvider 实例
     */
    public static EmbeddingProvider fromAgentConfig(AgentConfig agentConfig) {
        // 【从 AgentConfig 构造临时 Config 对象】
        Config tempConfig = new Config();
        tempConfig.setProviders(agentConfig.getProviders());
        return fromConfig(tempConfig);
    }

    /**
     * 【从 Config 创建 EmbeddingProvider】
     *
     * 自动识别提供商类型：
     * - DashScope/阿里云 -> QwenEmbeddingProvider
     * - 其他 -> OpenAIEmbeddingProvider
     *
     * @param config 全局配置
     * @return EmbeddingProvider 实例，如果未配置则返回 null
     */
    public static EmbeddingProvider fromConfig(Config config) {
        if (config.getProviders() == null) {
            return null;
        }

        ProviderConfig embeddingConfig = config.getProviders().get("embedding");

        if (embeddingConfig != null) {
            String apiBase = embeddingConfig.getApiBase();
            String model = embeddingConfig.getModel();

            // 【策略 1：根据 apiBase 自动识别】
            if (apiBase != null && (apiBase.contains("dashscope") || apiBase.contains("aliyun"))) {
                log.info("[EmbeddingProviderFactory] 检测到阿里云 DashScope 配置，使用 QwenEmbeddingProvider");
                return new QwenEmbeddingProvider(config);
            }

            // 【策略 2：根据 model 名称识别】
            if (model != null && model.toLowerCase().contains("text-embedding")) {
                // 如果模型名不是 OpenAI 的标准命名，尝试使用 QwenProvider
                if (!model.startsWith("text-embedding-3") && !model.startsWith("text-embedding-ada")) {
                    log.info("[EmbeddingProviderFactory] 检测到非 OpenAI 标准模型名 '{}', 尝试使用 QwenEmbeddingProvider", model);
                    return new QwenEmbeddingProvider(config);
                }
            }
        }

        // 【默认使用 OpenAI 兼容格式】
        log.info("[EmbeddingProviderFactory] 使用 OpenAICompatible EmbeddingProvider");
        return new OpenAIEmbeddingProvider(config);
    }

    /**
     * 【从 ProviderConfig 创建 EmbeddingProvider】
     *
     * @param providerConfig 提供商配置
     * @return EmbeddingProvider 实例
     */
    public static EmbeddingProvider fromProviderConfig(ProviderConfig providerConfig) {
        Config tempConfig = new Config();
        tempConfig.getProviders().put("embedding", providerConfig);
        return fromConfig(tempConfig);
    }
}
