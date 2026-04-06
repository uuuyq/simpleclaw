package com.simpleclaw.agent.skills.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 【技能元数据类】
 *
 * 对应 YAML frontmatter 中 metadata.openclaw 的扩展字段。
 * 用于运行时环境检测和技能加载控制。
 */
public class SkillMetadata {

    /** 是否始终加载（忽略 env/bin 检测） */
    private boolean always;

    /** UI 展示用图标 */
    private String emoji;

    /** 覆盖默认的配置 key（默认等于 name） */
    private String skillKey;

    /** 主 API Key 环境变量名（UI 可配置） */
    private String primaryEnv;

    /** 限定适用平台，如 ["darwin", "linux"] */
    private List<String> os;

    /** 运行时依赖要求 */
    private SkillRequires requires;

    /** 安装规格列表 */
    private List<Map<String, Object>> install;

    public SkillMetadata() {
        this.always = false;
        this.os = Collections.emptyList();
        this.requires = new SkillRequires();
        this.install = Collections.emptyList();
    }

    // ==================== Getters & Setters ====================

    public boolean isAlways() {
        return always;
    }

    public void setAlways(boolean always) {
        this.always = always;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public String getSkillKey() {
        return skillKey;
    }

    public void setSkillKey(String skillKey) {
        this.skillKey = skillKey;
    }

    public String getPrimaryEnv() {
        return primaryEnv;
    }

    public void setPrimaryEnv(String primaryEnv) {
        this.primaryEnv = primaryEnv;
    }

    public List<String> getOs() {
        return os != null ? os : Collections.emptyList();
    }

    public void setOs(List<String> os) {
        this.os = os;
    }

    public SkillRequires getRequires() {
        return requires != null ? requires : new SkillRequires();
    }

    public void setRequires(SkillRequires requires) {
        this.requires = requires;
    }

    public List<Map<String, Object>> getInstall() {
        return install != null ? install : Collections.emptyList();
    }

    public void setInstall(List<Map<String, Object>> install) {
        this.install = install;
    }

    /**
     * 【检查平台限制】
     * @param currentOs 当前操作系统名称（如 "windows", "linux", "mac"）
     * @return 如果无限制或当前平台在允许列表内，返回 true
     */
    public boolean isOsAllowed(String currentOs) {
        if (os == null || os.isEmpty()) {
            return true;
        }
        return os.stream().anyMatch(o -> o.equalsIgnoreCase(currentOs));
    }

    @Override
    public String toString() {
        return "SkillMetadata{" +
                "always=" + always +
                ", emoji='" + emoji + '\'' +
                ", primaryEnv='" + primaryEnv + '\'' +
                ", os=" + os +
                '}';
    }
}
