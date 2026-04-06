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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【通义千问 Embedding 提供商】
 * 
 * 适配阿里云 DashScope (通义千问) 的 Embedding API。
 * 参考文档: https://help.aliyun.com/zh/dashscope/developer-reference/text-embedding-api-details
 */
@Slf4j
public class QwenEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "text-embedding-v3";
    private static final int DEFAULT_DIMENSION = 1024; // v3 默认维度，也可选 512, 768, 1536

    private final String apiKey;
    private final String apiBase;
    private final String model;
    private final int dimension;

    public QwenEmbeddingProvider(Config config) {
        Map<String, ProviderConfig> providers = config.getProviders();
        ProviderConfig providerConfig = providers != null ? providers.get("embedding") : null;

        if (providerConfig == null) {
            throw new IllegalArgumentException("Missing 'embedding' provider configuration");
        }

        this.apiKey = providerConfig.getApiKey();
        this.apiBase = providerConfig.getApiBase() != null ? providerConfig.getApiBase() : "https://dashscope.aliyuncs.com/compatible-mode/v1";
        this.model = providerConfig.getModel() != null ? providerConfig.getModel() : DEFAULT_MODEL;
        this.dimension = providerConfig.getDimension() != null && providerConfig.getDimension() > 0 
                ? providerConfig.getDimension() 
                : DEFAULT_DIMENSION;

        if (this.apiKey == null || this.apiKey.isEmpty()) {
            throw new IllegalArgumentException("Missing API key for Qwen embedding provider");
        }
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            return new float[dimension];
        }

        try {
            String url = apiBase.endsWith("/") ? apiBase + "embeddings" : apiBase + "/embeddings";
            
            Map<String, Object> payload = Map.of(
                "model", model,
                "input", List.of(text),
                "encoding_format", "float"
            );

            String jsonBody = MAPPER.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[QwenEmbedding] API Error: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Qwen Embedding API error: " + response.statusCode());
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode embeddingNode = data.get(0).get("embedding");
                if (embeddingNode != null && embeddingNode.isArray()) {
                    float[] vector = new float[embeddingNode.size()];
                    for (int i = 0; i < embeddingNode.size(); i++) {
                        vector[i] = (float) embeddingNode.get(i).asDouble();
                    }
                    return vector;
                }
            }
            throw new RuntimeException("Failed to parse embedding from Qwen API response");

        } catch (Exception e) {
            log.error("[QwenEmbedding] Embedding failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public float[][] embedBatch(String[] texts) {
        float[][] results = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            results[i] = embed(texts[i]);
        }
        return results;
    }

    @Override
    public int getDimension() {
        return dimension;
    }
}
