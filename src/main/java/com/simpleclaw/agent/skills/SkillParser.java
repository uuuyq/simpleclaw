package com.simpleclaw.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.simpleclaw.agent.skills.model.Skill;
import com.simpleclaw.agent.skills.model.SkillMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 【技能文件解析器】
 *
 * 负责解析 SKILL.md 文件，提取 YAML frontmatter 和 Markdown 正文。
 * 对应文档 12.1 节描述的 Skill 文件结构。
 */
public class SkillParser {

    /** 默认单技能文件最大字节数（256KB） */
    private static final long DEFAULT_MAX_SKILL_FILE_BYTES = 256 * 1024;

    private final ObjectMapper yamlMapper;
    private final long maxFileBytes;

    public SkillParser() {
        this(DEFAULT_MAX_SKILL_FILE_BYTES);
    }

    public SkillParser(long maxFileBytes) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * 【解析技能文件】
     *
     * 解析 SKILL.md 文件，提取：
     * 1. YAML frontmatter（--- ... --- 之间的内容）
     * 2. Markdown 正文（frontmatter 之后的内容）
     *
     * @param skillFile 技能文件路径（SKILL.md）
     * @param skillRoot 技能根目录
     * @param source 技能来源标签
     * @param fallbackName 如果 frontmatter 中没有 name，使用此名称
     * @return 解析后的 Skill 对象
     */
    public Optional<Skill> parseSkill(Path skillFile, Path skillRoot, String source, String fallbackName) {
        // 【文件大小检查】
        try {
            long size = Files.size(skillFile);
            if (size > maxFileBytes) {
                System.err.println("[SkillParser] 技能文件过大，跳过: " + skillFile + " (" + size + " bytes)");
                return Optional.empty();
            }
        } catch (IOException e) {
            System.err.println("[SkillParser] 无法读取文件大小: " + skillFile);
            return Optional.empty();
        }

        // 【读取文件内容】
        String rawContent;
        try {
            rawContent = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[SkillParser] 读取技能文件失败: " + skillFile);
            return Optional.empty();
        }

        // 【解析 frontmatter 和正文】
        return parseContent(rawContent, skillFile, skillRoot, source, fallbackName);
    }

    /**
     * 【解析技能内容】
     *
     * 从字符串解析 frontmatter 和正文
     */
    private Optional<Skill> parseContent(String rawContent, Path skillFile, Path skillRoot,
                                          String source, String fallbackName) {
        String frontmatter = "";
        String bodyContent = rawContent;

        // 【提取 frontmatter】格式: ---\n...YAML...\n---\n...
        String trimmed = rawContent.trim();
        if (trimmed.startsWith("---")) {
            int endIdx = trimmed.indexOf("---", 3);
            if (endIdx > 3) {
                frontmatter = trimmed.substring(3, endIdx).trim();
                bodyContent = trimmed.substring(endIdx + 3).trim();
            }
        }

        // 【解析 YAML frontmatter】
        Map<String, Object> yamlData = Collections.emptyMap();
        if (!frontmatter.isEmpty()) {
            try {
                yamlData = yamlMapper.readValue(frontmatter, Map.class);
            } catch (Exception e) {
                System.err.println("[SkillParser] YAML 解析失败: " + skillFile + " - " + e.getMessage());
                // 继续使用空 frontmatter
            }
        }

        // 【提取基本字段】
        String name = getString(yamlData, "name", fallbackName);
        String description = getString(yamlData, "description", "");
        String homepage = getString(yamlData, "homepage", null);
        boolean userInvocable = getBoolean(yamlData, "user-invocable", true);
        boolean disableModelInvocation = getBoolean(yamlData, "disable-model-invocation", false);

        // 【解析 metadata.openclaw】
        SkillMetadata metadata = parseMetadata(yamlData);

        // 【创建 Skill 对象】
        Skill skill = new Skill(
                name,
                description,
                homepage,
                userInvocable,
                disableModelInvocation,
                metadata,
                skillFile,
                skillRoot,
                source,
                rawContent,
                bodyContent
        );

        return Optional.of(skill);
    }

    /**
     * 【解析 metadata 字段】
     */
    private SkillMetadata parseMetadata(Map<String, Object> yamlData) {
        SkillMetadata metadata = new SkillMetadata();

        Object metadataObj = yamlData.get("metadata");
        if (!(metadataObj instanceof Map)) {
            return metadata;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = (Map<String, Object>) metadataObj;

        Object openclawObj = metadataMap.get("openclaw");
        if (!(openclawObj instanceof Map)) {
            return metadata;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> openclaw = (Map<String, Object>) openclawObj;

        // 【解析基本字段】
        if (openclaw.get("always") instanceof Boolean) {
            metadata.setAlways((Boolean) openclaw.get("always"));
        }
        if (openclaw.get("emoji") instanceof String) {
            metadata.setEmoji((String) openclaw.get("emoji"));
        }
        if (openclaw.get("skillKey") instanceof String) {
            metadata.setSkillKey((String) openclaw.get("skillKey"));
        }
        if (openclaw.get("primaryEnv") instanceof String) {
            metadata.setPrimaryEnv((String) openclaw.get("primaryEnv"));
        }

        // 【解析 os 列表】
        if (openclaw.get("os") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<String> osList = (java.util.List<String>) openclaw.get("os");
            metadata.setOs(osList);
        }

        // 【解析 requires】
        if (openclaw.get("requires") instanceof Map) {
            metadata.setRequires(parseRequires((Map<String, Object>) openclaw.get("requires")));
        }

        // 【解析 install】
        if (openclaw.get("install") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> installList =
                    (java.util.List<Map<String, Object>>) openclaw.get("install");
            metadata.setInstall(installList);
        }

        return metadata;
    }

    /**
     * 【解析 requires 字段】
     */
    private com.simpleclaw.agent.skills.model.SkillRequires parseRequires(Map<String, Object> requiresMap) {
        com.simpleclaw.agent.skills.model.SkillRequires requires =
                new com.simpleclaw.agent.skills.model.SkillRequires();

        // 【解析 bins】
        if (requiresMap.get("bins") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<String> bins = (java.util.List<String>) requiresMap.get("bins");
            requires.setBins(bins);
        }

        // 【解析 anyBins】
        if (requiresMap.get("anyBins") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<String> anyBins = (java.util.List<String>) requiresMap.get("anyBins");
            requires.setAnyBins(anyBins);
        }

        // 【解析 env】
        if (requiresMap.get("env") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<String> env = (java.util.List<String>) requiresMap.get("env");
            requires.setEnv(env);
        }

        // 【解析 config】
        if (requiresMap.get("config") instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<String> config = (java.util.List<String>) requiresMap.get("config");
            requires.setConfig(config);
        }

        return requires;
    }

    // ==================== 辅助方法 ====================

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }
}
