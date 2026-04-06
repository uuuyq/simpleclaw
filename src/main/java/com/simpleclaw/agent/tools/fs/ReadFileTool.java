package com.simpleclaw.agent.tools.fs;

import com.simpleclaw.agent.tools.BaseTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 读取文件内容。参数 path。若 restrictToWorkspace 为 true 则限制在工作区内。
 */
public class ReadFileTool extends BaseTool {

    private final Path workspace;
    private final boolean restrictToWorkspace;

    public ReadFileTool(Path workspace, boolean restrictToWorkspace) {
        // 将工作区路径转换为绝对路径，确保路径比较正确
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : Paths.get(".").toAbsolutePath().normalize();
        this.restrictToWorkspace = restrictToWorkspace;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file. Use path relative to workspace or absolute if allowed.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "Relative or absolute file path");
        params.put("properties", Collections.singletonMap("path", path));
        params.put("required", Collections.singletonList("path"));
        return params;
    }

    @Override
    public String execute(Map<String, Object> params) {
        Object p = params.get("path");
        if (p == null || !(p instanceof String)) {
            return "[Error: path is required]";
        }
        Path path = Paths.get((String) p);
        if (!path.isAbsolute()) {
            path = workspace.resolve((String) p);
        }
        // 将目标路径也转换为绝对路径后再比较
        path = path.toAbsolutePath().normalize();
        if (restrictToWorkspace && !path.startsWith(workspace)) {
            return "[Error: path outside workspace not allowed]";
        }
        if (!Files.isRegularFile(path)) {
            return "[Error: file not found or not a file: " + path + "]";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }
}
