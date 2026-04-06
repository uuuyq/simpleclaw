package com.simpleclaw.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleclaw.config.Config;
import com.simpleclaw.config.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * 【OpenAI Embedding 提供商实现】
 *
 * 使用 OpenAI API 进行文本向量化。
 *
 * 特点：
 * - 高质量向量（text-embedding-3-small: 1536维）
 * - 需要 API Key
 * - 有调用费用
 *
 * 模型选项：
 * - text-embedding-3-small: 1536维，性价比高
 * - text-embedding-3-large: 3072维，质量更好
 * - text-embedding-ada-002: 1536维，旧版
 */
@Slf4j
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    // 【向量维度】text-embedding-3-small 使用 1536 维
    private final int dimension;

    // 【模型名称】
    private final String model;

    // 【默认 API 基础 URL】
    private static final String DEFAULT_API_BASE = "https://api.openai.com/v1/embeddings";

    // 【实际使用的 API Base】
    private final String apiBase;

    // 【HTTP 客户端】
    private final HttpClient httpClient;

    // 【JSON 处理器】
    private final ObjectMapper objectMapper;

    // 【API Key】
    private final String apiKey;

    /**
     * 【构造函数】
     *
     * @param config 配置对象，包含 API Key 和 Base URL
     */
    public OpenAIEmbeddingProvider(Config config) {
        // 【优先从 embedding 配置块读取，兼容旧的 openai 配置】
        Map<String, ProviderConfig> providers = config.getProviders();
        ProviderConfig providerConfig = providers != null ? providers.get("embedding") : null;
        
        if (providerConfig == null) {
            providerConfig = providers != null ? providers.get("openai") : null;
        }

        this.apiKey = providerConfig != null && providerConfig.getApiKey() != null
                ? providerConfig.getApiKey()
                : System.getenv("OPENAI_API_KEY");

        // 【支持自定义 API Base，默认为 OpenAI 官方地址】
        String base = providerConfig != null ? providerConfig.getApiBase() : null;
        this.apiBase = base != null
                ? base.endsWith("/embeddings") 
                    ? base 
                    : base.replaceAll("/$", "") + "/embeddings"
                : DEFAULT_API_BASE;

        // 【支持自定义模型名】
        this.model = providerConfig != null && providerConfig.getModel() != null 
                ? providerConfig.getModel() 
                : "text-embedding-3-small";
        
        // 【根据模型名推断维度】
        if (this.model.contains("large")) {
            this.dimension = 3072;
        } else if (this.model.contains("ada")) {
            this.dimension = 1536;
        } else {
            this.dimension = 1536; // small 默认
        }

        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();

        if (this.apiKey == null || this.apiKey.isEmpty()) {
            log.warn("[Embedding] OpenAI API Key 未配置，向量记忆功能将不可用");
        }
        log.info("[Embedding] 使用 API Base: {}", this.apiBase);
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[dimension];
        }

        try {
            // 【构建请求体】
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", text);
            requestBody.put("model", model);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 【构建 HTTP 请求】
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // 【发送请求】
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 【解析响应】
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode embeddingNode = root.path("data").get(0).path("embedding");

                float[] embedding = new float[dimension];
                for (int i = 0; i < dimension && i < embeddingNode.size(); i++) {
                    embedding[i] = (float) embeddingNode.get(i).asDouble();
                }

                log.debug("[Embedding] 文本向量化成功，长度: {}", text.length());
                return embedding;

            } else {
                log.error("[Embedding] API 调用失败: {} - {}",
                        response.statusCode(), response.body());
                return new float[dimension];
            }

        } catch (Exception e) {
            log.error("[Embedding] 向量化失败: {}", e.getMessage());
            return new float[dimension];
        }
    }

    @Override
    public float[][] embedBatch(String[] texts) {
        // 【简化实现：逐个处理】
        // 实际应该使用批量 API 提高效率
        float[][] results = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            results[i] = embed(texts[i]);
        }
        return results;
    }
}
