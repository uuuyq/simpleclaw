package com.simpleclaw.agent.tools.fs;

import com.simpleclaw.agent.tools.BaseTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 列出目录下的文件/子目录。参数 path（可选，默认 workspace）。
 */
public class ListDirTool extends BaseTool {

    private final Path workspace;
    private final boolean restrictToWorkspace;

    public ListDirTool(Path workspace, boolean restrictToWorkspace) {
        // 将工作区路径转换为绝对路径，确保路径比较正确
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : Paths.get(".").toAbsolutePath().normalize();
        this.restrictToWorkspace = restrictToWorkspace;
    }

    @Override
    public String getName() {
        return "list_dir";
    }

    @Override
    public String getDescription() {
        return "List files and subdirectories in a directory.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "Directory path (default: workspace root)");
        params.put("properties", Collections.singletonMap("path", path));
        return params;
    }

    @Override
    public String execute(Map<String, Object> params) {
        Path path = workspace;
        Object p = params.get("path");
        if (p != null && p instanceof String && !((String) p).isEmpty()) {
            path = Paths.get((String) p);
            if (!path.isAbsolute()) {
                path = workspace.resolve((String) p);
            }
        }
        // 将目标路径也转换为绝对路径后再比较
        path = path.toAbsolutePath().normalize();
        if (restrictToWorkspace && !path.startsWith(workspace)) {
            return "[Error: path outside workspace not allowed]";
        }
        if (!Files.isDirectory(path)) {
            return "[Error: not a directory: " + path + "]";
        }
        try {
            return Files.list(path)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }
}
