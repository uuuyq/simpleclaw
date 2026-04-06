package com.simpleclaw.agent.tools.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleclaw.agent.tools.Tool;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 【记忆读取工具】
 *
 * 允许 LLM 读取指定记忆文件的特定行范围。
 *
 * 参考 OpenClaw 实现：
 * - 安全地读取 MEMORY.md 或 memory/*.md 的指定行
 * - 用于 memory_search 后获取完整内容
 * - 保持上下文小巧，只读取需要的行
 */
@Slf4j
public class MemoryGetTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workspace;

    public MemoryGetTool(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String getName() {
        return "memory_get";
    }

    @Override
    public String getDescription() {
        return "Safe snippet read from MEMORY.md or memory/*.md with optional " +
               "from/lines; use after memory_search to pull only the needed lines and keep " +
               "context small.";
    }

    @Override
    public Map<String, Object> getParameters() {
        // 构建 JSON Schema 格式的参数定义
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // path 参数
        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "Relative path to the memory file (e.g., 'MEMORY.md' or 'memory/2026-01-01.md')");
        properties.put("path", pathProp);

        // from 参数
        Map<String, Object> fromProp = new LinkedHashMap<>();
        fromProp.put("type", "integer");
        fromProp.put("description", "Starting line number (1-based, optional)");
        properties.put("from", fromProp);

        // lines 参数
        Map<String, Object> linesProp = new LinkedHashMap<>();
        linesProp.put("type", "integer");
        linesProp.put("description", "Number of lines to read (optional)");
        properties.put("lines", linesProp);

        schema.put("properties", properties);

        // 必需参数
        List<String> required = Collections.singletonList("path");
        schema.put("required", required);

        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        if (pathStr == null || pathStr.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("disabled", true);
            result.put("error", "Path is required");
            return toJson(result);
        }

        // 安全检查：确保路径在 workspace 内
        Path targetPath = workspace.resolve(pathStr).normalize();
        if (!targetPath.startsWith(workspace)) {
            Map<String, Object> result = new HashMap<>();
            result.put("disabled", true);
            result.put("error", "Invalid path: outside workspace");
            return toJson(result);
        }

        // 检查文件是否存在
        if (!Files.isRegularFile(targetPath)) {
            Map<String, Object> result = new HashMap<>();
            result.put("disabled", true);
            result.put("error", "File not found: " + pathStr);
            return toJson(result);
        }

        // 解析可选参数
        Integer fromLine = args.containsKey("from") ?
                ((Number) args.get("from")).intValue() : null;
        Integer lineCount = args.containsKey("lines") ?
                ((Number) args.get("lines")).intValue() : null;

        try {
            // 读取文件内容
            List<String> allLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);

            // 计算读取范围
            int startIndex = 0;
            int endIndex = allLines.size();

            if (fromLine != null && fromLine > 0) {
                startIndex = Math.min(fromLine - 1, allLines.size());
            }

            if (lineCount != null && lineCount > 0) {
                endIndex = Math.min(startIndex + lineCount, allLines.size());
            }

            // 提取指定行
            List<String> selectedLines = allLines.subList(startIndex, endIndex);
            String content = String.join("\n", selectedLines);

            // 构建结果
            Map<String, Object> result = new HashMap<>();
            result.put("path", pathStr);
            result.put("content", content);
            result.put("fromLine", startIndex + 1);
            result.put("toLine", endIndex);
            result.put("totalLines", allLines.size());

            log.info("[MemoryGetTool] 读取 {} 行 {}-{}", pathStr, startIndex + 1, endIndex);
            return toJson(result);

        } catch (IOException e) {
            log.error("[MemoryGetTool] 读取失败: {}", e.getMessage(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("disabled", true);
            result.put("error", "Read failed: " + e.getMessage());
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
