package com.simpleclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置加载器
 */
@Slf4j
public final class ConfigLoader {

    private static final String SIMPLE_CLAW = ".simpleclaw";
    private static final String CONFIG_YML = "config.yml";
    private static final String CONFIG_JSON = "config.json";
    private static final String DEFAULT_WORKSPACE = "workspace";
    private static final String SESSIONS_DIR = "sessions";

    // 【支持 YAML 和 JSON】
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    /**
     * 默认配置文件路径：优先项目根目录 config.yml，其次 ~/.simpleclaw/config.json
     */
    public static Path getConfigPath() {
        // 1. 检查项目根目录的 config.yml
        Path rootYml = Paths.get(CONFIG_YML);
        if (Files.isRegularFile(rootYml)) {
            return rootYml.toAbsolutePath();
        }
        // 2. 检查项目根目录的 config.json
        Path rootJson = Paths.get(CONFIG_JSON);
        if (Files.isRegularFile(rootJson)) {
            return rootJson.toAbsolutePath();
        }
        // 3. 兜底：用户目录下的 config.json
        return getDataDir().resolve(CONFIG_JSON);
    }

    /**
     * 数据目录（cron、sessions 的父目录）：~/.simpleclaw
     */
    public static Path getDataDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home).resolve(SIMPLE_CLAW);
    }

    /**
     * 默认工作区路径：~/.simpleclaw/workspace
     */
    public static Path getDefaultWorkspacePath() {
        return getDataDir().resolve(DEFAULT_WORKSPACE);
    }

    /**
     * 会话存储目录：~/.simpleclaw/sessions
     */
    public static Path getSessionsDir() {
        return getDataDir().resolve(SESSIONS_DIR);
    }

    /**
     * 从默认路径加载配置；若文件不存在或解析失败则返回带默认路径的 Config。
     */
    public static Config loadConfig() {
        return loadConfig(getConfigPath());
    }

    /**
     * 从指定路径加载配置；失败时返回默认 Config（resolved 路径仍为 simpleclaw 约定）。
     */
    public static Config loadConfig(Path configPath) {
        Config config;
        if (Files.isRegularFile(configPath)) {
            try {
                // 【根据后缀选择解析器】
                ObjectMapper mapper = configPath.toString().endsWith(".yml") || configPath.toString().endsWith(".yaml")
                        ? YAML_MAPPER : JSON_MAPPER;
                config = mapper.readValue(configPath.toFile(), Config.class);
                log.info("加载配置成功: {}", configPath);
            } catch (IOException e) {
                log.error("加载配置失败: {}", e.getMessage());
                config = new Config();
            }
        } else {
            log.warn("配置文件不存在: {}", configPath);
            config = new Config();
        }
        Path dataDir = getDataDir();
        config.setResolvedDataDir(dataDir);
        Path workspace = config.getAgents().getWorkspace() != null && !config.getAgents().getWorkspace().isEmpty()
                ? resolvePath(config.getAgents().getWorkspace())
                : getDefaultWorkspacePath();
        config.setResolvedWorkspacePath(workspace);
        return config;
    }

    /**
     * 将 Config 写回文件（默认路径）；优先使用 YAML 格式。
     */
    public static void saveConfig(Config config) {
        saveConfig(config, getConfigPath());
    }

    /**
     * 将 Config 写入指定路径。
     */
    public static void saveConfig(Config config, Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            ObjectMapper mapper = configPath.toString().endsWith(".yml") || configPath.toString().endsWith(".yaml")
                    ? YAML_MAPPER : JSON_MAPPER;
            mapper.writeValue(configPath.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config to " + configPath, e);
        }
    }

    private static Path resolvePath(String raw) {
        if (raw.startsWith("~")) {
            String rest = raw.length() > 1 && raw.charAt(1) == '/' ? raw.substring(2) : raw.substring(1);
            return Paths.get(System.getProperty("user.home")).resolve(rest);
        }
        return Paths.get(raw);
    }
}
