package com.simpleclaw.agent.skills.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * 【技能实体类】
 *
 * 表示一个加载的技能，包含从 SKILL.md 解析的元数据和内容。
 * 对应文档 12.1 节描述的 Skill 文件结构。
 */
public class Skill {

    /** 技能唯一标识名（YAML frontmatter 中的 name，fallback 为目录名） */
    private final String name;

    /** 一句话描述（展示在 System Prompt 的 <available_skills> 中） */
    private final String description;

    /** 官网链接（可选） */
    private final String homepage;

    /** 是否允许用户通过 /skill 命令直接调用（默认 true） */
    private final boolean userInvocable;

    /** 设为 true 则不出现在 System Prompt，仅供内部引用（默认 false） */
    private final boolean disableModelInvocation;

    /** OpenClaw 扩展元数据 */
    private final SkillMetadata metadata;

    /** 技能文件路径 */
    private final Path skillFilePath;

    /** 技能根目录（SKILL.md 所在目录） */
    private final Path skillRootDir;

    /** 技能来源标签 */
    private final String source;

    /** SKILL.md 完整内容（包含 frontmatter） */
    private final String rawContent;

    /** 正文内容（去除 frontmatter 后的 Markdown） */
    private final String bodyContent;

    public Skill(String name, String description, String homepage,
                 boolean userInvocable, boolean disableModelInvocation,
                 SkillMetadata metadata, Path skillFilePath, Path skillRootDir,
                 String source, String rawContent, String bodyContent) {
        this.name = name;
        this.description = description != null ? description : "";
        this.homepage = homepage;
        this.userInvocable = userInvocable;
        this.disableModelInvocation = disableModelInvocation;
        this.metadata = metadata != null ? metadata : new SkillMetadata();
        this.skillFilePath = skillFilePath;
        this.skillRootDir = skillRootDir;
        this.source = source;
        this.rawContent = rawContent;
        this.bodyContent = bodyContent;
    }

    // ==================== Getters ====================

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getHomepage() {
        return homepage;
    }

    public boolean isUserInvocable() {
        return userInvocable;
    }

    public boolean isDisableModelInvocation() {
        return disableModelInvocation;
    }

    public SkillMetadata getMetadata() {
        return metadata;
    }

    public Path getSkillFilePath() {
        return skillFilePath;
    }

    public Path getSkillRootDir() {
        return skillRootDir;
    }

    public String getSource() {
        return source;
    }

    public String getRawContent() {
        return rawContent;
    }

    public String getBodyContent() {
        return bodyContent;
    }

    /**
     * 【获取技能展示名称】
     * 用于 System Prompt 中的 <available_skills> 列表
     */
    public String getDisplayName() {
        return name;
    }

    /**
     * 【检查是否为常驻技能】
     * metadata.openclaw.always = true 时，忽略 env/bin 检测，始终加载
     */
    public boolean isAlwaysLoaded() {
        return metadata != null && metadata.isAlways();
    }

    /**
     * 【获取主环境变量名】
     * metadata.openclaw.primaryEnv 定义的主 API Key 环境变量
     */
    public String getPrimaryEnv() {
        return metadata != null ? metadata.getPrimaryEnv() : null;
    }

    @Override
    public String toString() {
        return "Skill{" +
                "name='" + name + '\'' +
                ", source='" + source + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
