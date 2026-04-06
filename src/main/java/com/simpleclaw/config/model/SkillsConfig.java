package com.simpleclaw.config.model;

import java.util.Collections;
import java.util.List;

/**
 * 【技能系统配置】
 *
 * 对应文档 12.2 - 12.4 节的容量限制和加载配置。
 */
public class SkillsConfig {

    /** 是否加载内置技能（默认 true） */
    private boolean loadBundled = true;

    /** 内置技能白名单（null 或空列表表示无限制） */
    private List<String> allowBundled;

    /** 被禁用的技能列表 */
    private List<String> disabledSkills;

    /** 额外技能目录列表 */
    private List<String> extraDirs;

    // ===== 容量限制 =====

    /** 每根最多候选目录数（默认 300） */
    private int maxCandidatesPerRoot = 300;

    /** 每源最多加载技能数（默认 200） */
    private int maxSkillsLoadedPerSource = 200;

    /** Prompt 最多展示技能数（默认 150） */
    private int maxSkillsInPrompt = 150;

    /** Prompt 最大字符数（默认 30000） */
    private int maxSkillsPromptChars = 30000;

    /** 单技能文件最大字节（默认 256KB） */
    private long maxSkillFileBytes = 256000;

    // ==================== Getters & Setters ====================

    public boolean isLoadBundled() {
        return loadBundled;
    }

    public void setLoadBundled(boolean loadBundled) {
        this.loadBundled = loadBundled;
    }

    public List<String> getAllowBundled() {
        return allowBundled != null ? allowBundled : Collections.emptyList();
    }

    public void setAllowBundled(List<String> allowBundled) {
        this.allowBundled = allowBundled;
    }

    public List<String> getDisabledSkills() {
        return disabledSkills != null ? disabledSkills : Collections.emptyList();
    }

    public void setDisabledSkills(List<String> disabledSkills) {
        this.disabledSkills = disabledSkills;
    }

    public List<String> getExtraDirs() {
        return extraDirs != null ? extraDirs : Collections.emptyList();
    }

    public void setExtraDirs(List<String> extraDirs) {
        this.extraDirs = extraDirs;
    }

    public int getMaxCandidatesPerRoot() {
        return maxCandidatesPerRoot;
    }

    public void setMaxCandidatesPerRoot(int maxCandidatesPerRoot) {
        this.maxCandidatesPerRoot = maxCandidatesPerRoot;
    }

    public int getMaxSkillsLoadedPerSource() {
        return maxSkillsLoadedPerSource;
    }

    public void setMaxSkillsLoadedPerSource(int maxSkillsLoadedPerSource) {
        this.maxSkillsLoadedPerSource = maxSkillsLoadedPerSource;
    }

    public int getMaxSkillsInPrompt() {
        return maxSkillsInPrompt;
    }

    public void setMaxSkillsInPrompt(int maxSkillsInPrompt) {
        this.maxSkillsInPrompt = maxSkillsInPrompt;
    }

    public int getMaxSkillsPromptChars() {
        return maxSkillsPromptChars;
    }

    public void setMaxSkillsPromptChars(int maxSkillsPromptChars) {
        this.maxSkillsPromptChars = maxSkillsPromptChars;
    }

    public long getMaxSkillFileBytes() {
        return maxSkillFileBytes;
    }

    public void setMaxSkillFileBytes(long maxSkillFileBytes) {
        this.maxSkillFileBytes = maxSkillFileBytes;
    }
}
