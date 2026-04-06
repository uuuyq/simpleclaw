package com.simpleclaw.agent.skills;

import com.simpleclaw.agent.skills.model.Skill;
import com.simpleclaw.agent.skills.model.SkillMetadata;
import com.simpleclaw.agent.skills.model.SkillRequires;
import com.simpleclaw.config.model.SkillsConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 【技能加载器】
 *
 * 负责从多个源加载技能，支持：
 * 1. 多源扫描（按优先级合并）
 * 2. 运行时环境检测
 * 3. 技能过滤和可见性控制
 * 4. Prompt 格式化（全格式 → 紧凑格式 → 截断）
 *
 * 对应文档 12.2 - 12.4 节。
 */
public class SkillsLoader {

    private static final String SKILL_FILE = "SKILL.md";
    private static final String SKILLS_SUBDIR = "skills";

    /** 默认每源最多加载技能数 */
    private static final int DEFAULT_MAX_SKILLS_PER_SOURCE = 200;
    /** 默认每根最多候选目录数 */
    private static final int DEFAULT_MAX_CANDIDATES_PER_ROOT = 300;

    private final Path workspace;
    private final Path dataDir;
    private final Path builtinSkillsDir;
    private final SkillsConfig config;
    private final SkillParser parser;

    /** 已加载的技能缓存：name -> Skill */
    private final Map<String, Skill> skillCache;

    /** 当前操作系统 */
    private final String currentOs;

    public SkillsLoader(Path workspace, Path dataDir, Path builtinSkillsDir, SkillsConfig config) {
        this.workspace = workspace;
        this.dataDir = dataDir;
        this.builtinSkillsDir = builtinSkillsDir;
        this.config = config != null ? config : new SkillsConfig();
        this.parser = new SkillParser(this.config.getMaxSkillFileBytes());
        this.skillCache = new LinkedHashMap<>();
        this.currentOs = detectOs();
    }

    // ==================== 公共 API ====================

    /**
     * 【加载所有可用技能】
     *
     * 按优先级从低到高扫描所有源，高优先级覆盖低优先级。
     * 同时进行运行时环境检测和过滤。
     */
    public Map<String, Skill> loadAllSkills() {
        skillCache.clear();

        // 【按优先级从低到高扫描】
        // 1. 内置捆绑技能
        if (builtinSkillsDir != null && shouldLoadBundled()) {
            loadFromDirectory(builtinSkillsDir, "openclaw-bundled");
        }

        // 2. 托管技能（~/.simpleclaw/skills/）
        if (dataDir != null) {
            Path managedDir = dataDir.resolve("skills");
            loadFromDirectory(managedDir, "openclaw-managed");
        }

        // 3. 工作区技能（<workspace>/skills/）
        if (workspace != null) {
            Path workspaceSkillsDir = workspace.resolve(SKILLS_SUBDIR);
            loadFromDirectory(workspaceSkillsDir, "openclaw-workspace");
        }

        // 4. 额外目录（配置中指定）
        if (config.getExtraDirs() != null) {
            for (String extraDir : config.getExtraDirs()) {
                loadFromDirectory(Path.of(extraDir), "openclaw-extra");
            }
        }

        return new LinkedHashMap<>(skillCache);
    }

    /**
     * 【获取启用的技能列表】
     *
     * 根据配置和运行时环境过滤，返回应在 System Prompt 中展示的技能。
     *
     * @param skillFilter 代理级过滤器（可选），仅保留指定名称的技能
     * @return 过滤后的技能列表
     */
    public List<Skill> getEnabledSkills(List<String> skillFilter) {
        Map<String, Skill> allSkills = loadAllSkills();
        List<Skill> enabled = new ArrayList<>();

        for (Skill skill : allSkills.values()) {
            // 【检查是否被用户禁用】
            if (isSkillDisabled(skill.getName())) {
                continue;
            }

            // 【检查 bundled 白名单】
            if ("openclaw-bundled".equals(skill.getSource()) && !isBundledAllowed(skill.getName())) {
                continue;
            }

            // 【运行时环境检测】
            if (!evaluateRuntimeEligibility(skill)) {
                continue;
            }

            // 【disableModelInvocation 标志】
            if (skill.isDisableModelInvocation()) {
                continue;
            }

            // 【代理级过滤器】
            if (skillFilter != null && !skillFilter.isEmpty()) {
                if (!skillFilter.contains(skill.getName())) {
                    continue;
                }
            }

            enabled.add(skill);
        }

        return enabled;
    }

    /**
     * 【获取常驻加载的技能】
     *
     * metadata.openclaw.always = true 的技能
     */
    public List<String> getAlwaysSkills() {
        return loadAllSkills().values().stream()
                .filter(Skill::isAlwaysLoaded)
                .map(Skill::getName)
                .collect(Collectors.toList());
    }

    /**
     * 【加载指定技能内容】
     *
     * 按名称查找技能，返回其正文内容（Markdown，不含 frontmatter）
     */
    public Optional<String> loadSkill(String name) {
        Skill skill = loadAllSkills().get(name);
        if (skill != null) {
            return Optional.of(skill.getBodyContent());
        }
        return Optional.empty();
    }

    /**
     * 【加载多个技能内容】
     *
     * 将多个技能内容拼接成一段文本，供 system prompt 使用
     */
    public String loadSkillsForContext(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : skillNames) {
            loadSkill(name).ifPresent(content -> {
                sb.append("### ").append(name).append("\n\n").append(content).append("\n\n");
            });
        }
        return sb.toString();
    }

    /**
     * 【构建技能摘要】
     *
     * 生成 "Available skills: skill1, skill2, ..." 格式的摘要
     */
    public String buildSkillsSummary() {
        List<Skill> skills = getEnabledSkills(null);
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Available skills: ");
        List<String> names = skills.stream()
                .map(Skill::getName)
                .collect(Collectors.toList());
        sb.append(String.join(", ", names)).append("\n");
        return sb.toString();
    }

    /**
     * 【格式化技能为 System Prompt】
     *
     * 三段式降级策略：
     * 1. 全格式（含 name, description, location）
     * 2. 紧凑格式（仅 name, location）
     * 3. 截断（二分搜索最大可容纳数）
     *
     * @param skills 技能列表
     * @param maxChars 最大字符预算
     * @return 格式化后的 Prompt 文本
     */
    public String formatSkillsForPrompt(List<Skill> skills, int maxChars) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }

        // 【尝试全格式】
        String fullFormat = formatSkillsFull(skills);
        if (fullFormat.length() <= maxChars) {
            return fullFormat;
        }

        // 【尝试紧凑格式】
        String compactFormat = formatSkillsCompact(skills);
        if (compactFormat.length() <= maxChars) {
            return compactFormat + "\n\n(⚠️ 技能描述已省略以节省空间)";
        }

        // 【二分搜索最大可容纳数】
        int maxSkills = findMaxSkillsCount(skills, maxChars);
        if (maxSkills > 0) {
            List<Skill> truncated = skills.subList(0, maxSkills);
            return formatSkillsCompact(truncated)
                    + "\n\n(⚠️ 仅展示前 " + maxSkills + " 个技能，共 " + skills.size() + " 个)";
        }

        return "";
    }

    // ==================== 内部方法：扫描加载 ====================

    /**
     * 【从目录加载技能】
     *
     * 单目录扫描逻辑：
     * 1. 若目录本身有 SKILL.md，将整个目录视为一个技能
     * 2. 否则遍历一级子目录，每个含 SKILL.md 的子目录是一个技能
     * 3. 过滤隐藏目录和 node_modules
     */
    private void loadFromDirectory(Path root, String source) {
        if (!Files.isDirectory(root)) {
            return;
        }

        // 【嵌套根检测】如果目录下有 skills/ 子目录且其中有 SKILL.md，使用 skills/ 作为扫描根
        Path nestedSkillsDir = root.resolve(SKILLS_SUBDIR);
        if (Files.isDirectory(nestedSkillsDir) && hasAnySkillMd(nestedSkillsDir)) {
            root = nestedSkillsDir;
        }

        // 【检查目录本身是否有 SKILL.md】
        Path rootSkillFile = root.resolve(SKILL_FILE);
        if (Files.isRegularFile(rootSkillFile)) {
            parseAndCache(rootSkillFile, root, source, root.getFileName().toString());
            return;
        }

        // 【遍历一级子目录】
        try {
            List<Path> candidates = Files.list(root)
                    .filter(Files::isDirectory)
                    .filter(this::isValidSkillDir)
                    .limit(config.getMaxCandidatesPerRoot() > 0
                            ? config.getMaxCandidatesPerRoot()
                            : DEFAULT_MAX_CANDIDATES_PER_ROOT)
                    .collect(Collectors.toList());

            int loadedCount = 0;
            int maxPerSource = config.getMaxSkillsLoadedPerSource() > 0
                    ? config.getMaxSkillsLoadedPerSource()
                    : DEFAULT_MAX_SKILLS_PER_SOURCE;

            for (Path dir : candidates) {
                if (loadedCount >= maxPerSource) {
                    break;
                }

                Path skillFile = dir.resolve(SKILL_FILE);
                if (Files.isRegularFile(skillFile)) {
                    String name = dir.getFileName().toString();
                    if (parseAndCache(skillFile, dir, source, name)) {
                        loadedCount++;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[SkillsLoader] 扫描目录失败: " + root + " - " + e.getMessage());
        }
    }

    /**
     * 【解析并缓存技能】
     *
     * 高优先级源覆盖低优先级源
     */
    private boolean parseAndCache(Path skillFile, Path skillRoot, String source, String fallbackName) {
        Optional<Skill> parsed = parser.parseSkill(skillFile, skillRoot, source, fallbackName);
        if (parsed.isPresent()) {
            Skill skill = parsed.get();
            // 高优先级覆盖低优先级
            skillCache.put(skill.getName(), skill);
            return true;
        }
        return false;
    }

    /**
     * 【检查目录是否为有效技能目录】
     *
     * 过滤隐藏目录（. 开头）和 node_modules
     */
    private boolean isValidSkillDir(Path dir) {
        String name = dir.getFileName().toString();
        return !name.startsWith(".") && !name.equals("node_modules");
    }

    /**
     * 【检查目录下是否有任何 SKILL.md】
     */
    private boolean hasAnySkillMd(Path dir) {
        try {
            return Files.list(dir)
                    .filter(Files::isDirectory)
                    .anyMatch(sub -> Files.isRegularFile(sub.resolve(SKILL_FILE)));
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 内部方法：过滤和检测 ====================

    /**
     * 【检查技能是否被用户禁用】
     */
    private boolean isSkillDisabled(String name) {
        if (config.getDisabledSkills() == null) {
            return false;
        }
        return config.getDisabledSkills().contains(name);
    }

    /**
     * 【检查是否加载 bundled 技能】
     */
    private boolean shouldLoadBundled() {
        return config.isLoadBundled();
    }

    /**
     * 【检查 bundled 技能是否在白名单内】
     */
    private boolean isBundledAllowed(String name) {
        if (config.getAllowBundled() == null || config.getAllowBundled().isEmpty()) {
            return true; // 无白名单限制，全部允许
        }
        return config.getAllowBundled().contains(name);
    }

    /**
     * 【运行时环境检测】
     *
     * 检查：
     * 1. os：当前平台在允许列表内
     * 2. requires.bins：所有必需二进制文件可执行
     * 3. requires.anyBins：至少一个 bin 可执行
     * 4. requires.env：所有必需 env 变量非空
     * 5. always=true：跳过所有检查
     */
    private boolean evaluateRuntimeEligibility(Skill skill) {
        SkillMetadata metadata = skill.getMetadata();

        // 【always=true 跳过所有检查】
        if (metadata.isAlways()) {
            return true;
        }

        // 【平台检测】
        if (!metadata.isOsAllowed(currentOs)) {
            return false;
        }

        SkillRequires requires = metadata.getRequires();
        if (!requires.hasRequirements()) {
            return true;
        }

        // 【bins 检测（AND 逻辑）】
        for (String bin : requires.getBins()) {
            if (!isCommandAvailable(bin)) {
                return false;
            }
        }

        // 【anyBins 检测（OR 逻辑）】
        if (!requires.getAnyBins().isEmpty()) {
            boolean anyAvailable = requires.getAnyBins().stream()
                    .anyMatch(this::isCommandAvailable);
            if (!anyAvailable) {
                return false;
            }
        }

        // 【env 变量检测】
        for (String env : requires.getEnv()) {
            if (System.getenv(env) == null || System.getenv(env).isEmpty()) {
                return false;
            }
        }

        // 【config 检测（简化实现，实际应从配置读取）】
        // 暂返回 true，后续可扩展

        return true;
    }

    /**
     * 【检测命令是否可用】
     */
    private boolean isCommandAvailable(String command) {
        String[] checkCmd = isWindows() ? new String[]{"where", command} : new String[]{"which", command};
        try {
            Process process = new ProcessBuilder(checkCmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 【检测当前操作系统】
     */
    private String detectOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        } else if (os.contains("mac")) {
            return "darwin";
        } else if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private boolean isWindows() {
        return "windows".equals(currentOs);
    }

    // ==================== 内部方法：格式化 ====================

    /**
     * 【全格式】包含 name, description, location
     */
    private String formatSkillsFull(List<Skill> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following skills provide specialized instructions for specific tasks.\n");
        sb.append("Use the read tool to load a skill's file when the task matches its name.\n");
        sb.append("When a skill file references a relative path, resolve it against the skill\n");
        sb.append("directory (parent of SKILL.md / dirname of the path) and use that absolute\n");
        sb.append("path in tool commands.\n\n");
        sb.append("<available_skills>\n");

        for (Skill skill : skills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(skill.getName())).append("</name>\n");
            if (!skill.getDescription().isEmpty()) {
                sb.append("    <description>").append(escapeXml(skill.getDescription())).append("</description>\n");
            }
            sb.append("    <location>").append(compactPath(skill.getSkillFilePath())).append("</location>\n");
            sb.append("  </skill>\n");
        }

        sb.append("</available_skills>");
        return sb.toString();
    }

    /**
     * 【紧凑格式】仅 name + location
     */
    private String formatSkillsCompact(List<Skill> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following skills provide specialized instructions for specific tasks.\n\n");
        sb.append("<available_skills>\n");

        for (Skill skill : skills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(skill.getName())).append("</name>\n");
            sb.append("    <location>").append(compactPath(skill.getSkillFilePath())).append("</location>\n");
            sb.append("  </skill>\n");
        }

        sb.append("</available_skills>");
        return sb.toString();
    }

    /**
     * 【二分搜索最大可容纳技能数】
     */
    private int findMaxSkillsCount(List<Skill> skills, int maxChars) {
        int left = 0;
        int right = skills.size();
        int result = 0;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            if (mid == 0) {
                left = mid + 1;
                continue;
            }

            List<Skill> subset = skills.subList(0, mid);
            String formatted = formatSkillsCompact(subset);

            if (formatted.length() <= maxChars) {
                result = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }

        return result;
    }

    /**
     * 【路径压缩】将 $HOME 替换为 ~/
     */
    private String compactPath(Path path) {
        String home = System.getProperty("user.home");
        String pathStr = path.toString();
        if (pathStr.startsWith(home)) {
            return "~" + pathStr.substring(home.length());
        }
        return pathStr;
    }

    /**
     * 【XML 转义】
     */
    private String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
