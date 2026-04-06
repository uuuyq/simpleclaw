package com.simpleclaw.agent.tools.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleclaw.agent.tools.Tool;
import com.simpleclaw.memory.MemoryManager;
import com.simpleclaw.memory.model.MemoryChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 【记忆搜索工具】
 *
 * 允许 LLM 主动搜索长期记忆，基于混合检索（向量 + BM25）。
 *
 * 参考 OpenClaw 实现：
 * - 工具描述强调 "Mandatory recall step"
 * - 返回片段包含路径、行号、内容
 * - 支持配置 maxResults 和 minScore
 */
@Slf4j
public class MemorySearchTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemoryManager memoryManager;

    public MemorySearchTool(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public String getName() {
        return "memory_search";
    }

    @Override
    public String getDescription() {
        return "Mandatory recall step: semantically search MEMORY.md + memory/*.md " +
               "(and optional session transcripts) before answering questions about prior work, " +
               "decisions, dates, people, preferences, or todos; returns top snippets with " +
               "path + lines. If response has disabled=true, memory retrieval is unavailable " +
               "and should be surfaced to the user.";
    }

    @Override
    public Map<String, Object> getParameters() {
        // 构建 JSON Schema 格式的参数定义
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // query 参数
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "Search query for memory retrieval");
        properties.put("query", queryProp);

        // maxResults 参数
        Map<String, Object> maxResultsProp = new LinkedHashMap<>();
        maxResultsProp.put("type", "integer");
        maxResultsProp.put("description", "Maximum number of results to return (optional, overrides default)");
        properties.put("maxResults", maxResultsProp);

        // minScore 参数
        Map<String, Object> minScoreProp = new LinkedHashMap<>();
        minScoreProp.put("type", "number");
        minScoreProp.put("description", "Minimum relevance score threshold (optional, overrides default)");
        properties.put("minScore", minScoreProp);

        schema.put("properties", properties);

        // 必需参数
        List<String> required = Collections.singletonList("query");
        schema.put("required", required);

        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        // 检查记忆功能是否可用
        if (memoryManager == null || !memoryManager.isEnabled()) {
            Map<String, Object> result = new HashMap<>();
            result.put("disabled", true);
            result.put("error", "Memory retrieval is not available");
            return toJson(result);
        }

        String query = (String) args.get("query");
        if (query == null || query.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("results", Collections.emptyList());
            return toJson(result);
        }

        // 解析可选参数
        Integer maxResults = args.containsKey("maxResults") ?
                ((Number) args.get("maxResults")).intValue() : null;
        Float minScore = args.containsKey("minScore") ?
                ((Number) args.get("minScore")).floatValue() : null;

        try {
            // 执行搜索
            List<MemoryChunk> chunks = memoryManager.searchMemory(query, maxResults, minScore);

            // 构建结果
            List<Map<String, Object>> results = new ArrayList<>();
            for (MemoryChunk chunk : chunks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", chunk.getSourcePath() != null ? chunk.getSourcePath() : chunk.getSource());
                item.put("content", chunk.getContent());
                item.put("score", chunk.getScore());
                results.add(item);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("results", results);
            result.put("citations", "on");
            result.put("mode", "hybrid");

            log.info("[MemorySearchTool] 查询 '{}' 返回 {} 条结果", query, results.size());
            return toJson(result);

        } catch (Exception e) {
            log.error("[MemorySearchTool] 搜索失败: {}", e.getMessage(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("disabled", true);
            result.put("error", "Search failed: " + e.getMessage());
            return toJson(result);
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"JSON serialization failed\"}";
        }
    }
}
