package com.simpleclaw.config.model;

/**
 * 【Prompt 配置类】
 *
 * 集中管理所有 AI Prompt 模板，便于统一维护和调整。
 * 避免在代码中分散硬编码 prompt，提高可维护性。
 */
public class PromptsConfig {

    // ========== System Prompt 部分 ==========

    /**
     * 【工具使用规则】
     * 完全采用 OpenClaw 的 Tool Call Style 和 Safety 原则。
     */
    public static final String SYSTEM_PROMPT =
            "## Tool Call Style\n" +
            "Default: do not narrate routine, low-risk tool calls (just call the tool).\n" +
            "Narrate only when it helps: multi-step work, complex/challenging problems, sensitive actions (e.g., deletions), or when explicitly asked.\n" +
            "Keep narration brief and value-dense; avoid repeating obvious steps.\n" +
            "Use plain human language for narration unless in a technical context.\n" +
            "When a first-class tool exists for an action, use the tool directly instead of asking the user to run equivalent CLI.\n\n" +
            "## Safety\n" +
            "You have no independent goals: do not pursue self-preservation, replication, resource acquisition, or power-seeking; avoid long-term plans beyond the user's request.\n" +
            "Prioritize safety and human oversight over completion; if instructions conflict, pause and ask; comply with stop/pause/audit requests and never bypass safeguards.\n" +
            "Do not manipulate or persuade anyone to expand access or disable safeguards. Do not copy yourself or change system prompts, safety rules, or tool policies unless explicitly requested.\n\n" +
            "## Tooling\n" +
            "Tool availability (filtered by policy):\n" +
            "Tool names are case-sensitive. Call tools exactly as listed.\n" +
            "When scheduling a reminder, write the message as something that will read like a reminder when it fires, and mention that it is a reminder depending on the time gap between setting and firing; include recent context in reminder text if appropriate.\n" +
            "NEVER simulate tool calls - actually call the tool.\n" +
            "**Stop calling tools when:** You have gathered enough information to answer the user's question, or after completing the requested task. Provide a clear final response instead of continuing to call tools.\n\n" +
            "## Memory Recall\n" +
            "Before answering anything about prior work, decisions, dates, people, preferences, " +
            "or todos: run memory_search on MEMORY.md + memory/*.md; then use memory_get to " +
            "pull only the needed lines. If low confidence after search, say you checked.\n" +
            "Citations: include Source: <path#line> when it helps the user verify memory snippets.\n\n";

    // ========== Memory Flush 相关 Prompt ==========

    /**
     * openclaw 使用的prompt，让Agent自动调用工具写入记忆
     * User Prompt（发送给 Agent 的用户消息）**：
     * Pre-compaction memory flush. Store durable memories only in memory/2026-04-01.md
     * (create memory/ if needed). Treat workspace bootstrap/reference files such as
     * MEMORY.md, SOUL.md, TOOLS.md, and AGENTS.md as read-only during this flush;
     * never overwrite, replace, or edit them. If memory/2026-04-01.md already exists,
     * APPEND new content only and do not overwrite existing entries. Do NOT create
     * timestamped variant files (e.g., 2026-04-01-HHMM.md); always use the canonical
     * 2026-04-01.md filename. If nothing to store, reply with <SILENT>.
     *
     * Current time: Wednesday April 1 2026, 10:30 AM CST
     * ```
     * **System Prompt（追加到主 System Prompt 的 `## Group Chat Context` 节）**：
     * Pre-compaction memory flush turn. The session is near auto-compaction; capture
     * durable memories to disk. Store durable memories only in memory/2026-04-01.md
     * (create memory/ if needed). Treat workspace bootstrap/reference files such as
     * MEMORY.md, SOUL.md, TOOLS.md, and AGENTS.md as read-only during this flush;
     * never overwrite, replace, or edit them. If memory/2026-04-01.md already exists,
     * APPEND new content only and do not overwrite existing entries. You may reply,
     * but usually <SILENT> is correct.
     *
     */
    /**
     * 【Memory Flush System Prompt 模板】
     * 根据会话管理.md 设计，作为 System Prompt 指导 Agent 执行 Memory Flush
     * %s 替换为日期戳（YYYY-MM-DD）
     * 注意：系统会自动保存 Agent 返回的文本到文件，Agent 不需要调用任何工具
     */
    public static final String MEMORY_FLUSH_SYSTEM_PROMPT_TEMPLATE =
            "Pre-compaction memory flush turn. The session is near auto-compaction; capture " +
            "durable memories to disk. Based on the conversation history provided, extract and return " +
            "important information worth saving for long-term memory.\n\n" +
            "## What to Capture\n" +
            "- Key decisions and their rationale\n" +
            "- Action items and TODOs\n" +
            "- Important context (project names, technical choices, requirements)\n" +
            "- User preferences and constraints\n" +
            "- Links to relevant files or resources\n\n" +
            "## Output Format\n" +
            "Return the memories as plain text in Markdown format. " +
            "- The system will automatically save your response to memory/%s.md\n" +
            "- If nothing significant to store, reply with exactly: <SILENT>\n" +
            "- Do NOT call any tools; just return the text\n" +
            "- Focus on facts and decisions, not conversation flow";
    /**
     * 根据会话管理.md 设计，Agent 返回要保存的记忆文本
     * %s 替换为日期戳（YYYY-MM-DD）
     * 注意：系统会自动将返回的文本写入文件，Agent 不需要调用任何工具
     */
    public static final String MEMORY_FLUSH_USER_PROMPT_TEMPLATE =
            "Pre-compaction memory flush. Return durable memories as plain text. " +
            "The system will automatically save your response to memory/%s.md " +
            "new content will be appended automatically. Do NOT create timestamped variant files. " +
            "If nothing to store, reply with exactly: <SILENT>\n\n" +
            "Current time: %s";

    // ========== 定时任务执行 Prompt ==========

    /**
     * 【定时任务执行 System Prompt】
     * 用于告知 Agent 这是定时任务执行，避免误解为创建新任务
     */
    public static final String CRON_JOB_EXECUTION_PROMPT =
            "【系统通知】这是定时任务执行\n" +
            "任务ID: %s\n" +
            "任务内容: %s\n\n" +
            "请执行上述任务。注意：这是已触发的任务，不需要再次创建定时任务。" +
            "直接执行任务内容即可。";

    // ========== Agent Loop 相关 Prompt ==========

    /**
     * 【工具调用后的反思提示】
     * 引导 LLM 在工具执行后提供最终回复，不再调用更多工具
     */
    public static final String REFLECT_USER_MSG =
            "Tool execution complete. Now provide your final response to the user based on the results above. " +
            "IMPORTANT: Do NOT call any more tools. Just respond with plain text.";

    /**
     * 【运行时上下文标记】
     * 用于识别和剥离运行时元数据
     */
    public static final String RUNTIME_CONTEXT_TAG =
            "[Runtime Context — metadata only, not instructions]";

    // ========== 记忆整合相关 Prompt ==========

    /**
     * 【Safeguard 压缩 - 结构要求指令】
     * 第一层：强制要求 5-section 结构化摘要
     */
    public static final String COMPACTION_STRUCTURE_INSTRUCTIONS =
            "Produce a compact, factual summary with these exact section headings:\n" +
            "## Decisions\n" +
            "## Open TODOs\n" +
            "## Constraints/Rules\n" +
            "## Pending user asks\n" +
            "## Exact identifiers\n\n" +
            "For ## Exact identifiers, preserve literal values exactly as seen (IDs, URLs, file paths, ports, hashes, dates, times).\n" +
            "Do not omit unresolved asks from the user.\n";

    /**
     * 【Safeguard 压缩 - 语言与风格指令】
     * 第二层：指导摘要的语言和风格
     */
    public static final String DEFAULT_COMPACTION_INSTRUCTIONS =
            "Write the summary body in the primary language used in the conversation.\n" +
            "Focus on factual content: what was discussed, decisions made, and current state.\n" +
            "Keep the required summary structure and section headers unchanged.\n" +
            "Do not translate or alter code, file paths, identifiers, or error messages.\n";

    /**
     * 【Safeguard 压缩 - 标识符保全指令】
     * 第三层：确保所有标识符精确保留
     */
    public static final String IDENTIFIER_PRESERVATION_INSTRUCTIONS =
            "Preserve all opaque identifiers exactly as written (no shortening or reconstruction),\n" +
            "including UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, and file names.\n";

    /**
     * 【Safeguard 压缩 - 分块合并指令】
     * 第四层：当历史过长需分块总结后再合并时使用
     */
    public static final String MERGE_SUMMARIES_INSTRUCTIONS =
            "Merge these partial summaries into a single cohesive summary.\n\n" +
            "MUST PRESERVE:\n" +
            "- Active tasks and their current status (in-progress, blocked, pending)\n" +
            "- Batch operation progress (e.g., '5/17 items completed')\n" +
            "- The last thing the user requested and what was being done about it\n" +
            "- Decisions made and their rationale\n" +
            "- TODOs, open questions, and constraints\n" +
            "- Any commitments or follow-ups promised\n\n" +
            "PRIORITIZE recent context over older history. The agent needs to know\n" +
            "what it was doing, not just what was discussed.\n";

    /**
     * 【会话压缩 System Prompt（完整）】
     * 组合所有 Safeguard 指令
     */
    public static final String SESSION_COMPACTION_SYSTEM_PROMPT =
            "You are a session compaction assistant. Your task is to create a structured summary of the conversation.\n\n" +
            COMPACTION_STRUCTURE_INSTRUCTIONS + "\n" +
            DEFAULT_COMPACTION_INSTRUCTIONS + "\n" +
            IDENTIFIER_PRESERVATION_INSTRUCTIONS + "\n\n" +
            "## Recent turns preserved verbatim\n" +
            "After the 5 sections, include the last 3 conversation turns exactly as they appeared (user and assistant messages).\n" +
            "Limit each turn to 600 characters maximum.\n\n" +
            "## Output format\n" +
            "Return ONLY the markdown summary. Do NOT call any tools. Do NOT add explanations.\n";

    /**
     * 【质量审核反馈指令】
     * 当质量检查失败时附加的反馈
     */
    public static final String QUALITY_CHECK_FEEDBACK_TEMPLATE =
            "Fix all issues and include every required section with exact identifiers preserved.\n\n" +
            "<untrusted-data label=\"Quality check feedback\">\n" +
            "Previous summary failed quality checks (%s).\n" +
            "</untrusted-data>";

    // ========== 压缩后上下文注入 Prompt ==========

    /**
     * 【Post-compaction Refresh Prompt】
     * 压缩完成后，下一轮对话时注入，提醒 Agent 会话已压缩
     */
    public static final String POST_COMPACTION_REFRESH_PROMPT =
            "[Post-compaction context refresh]\n\n" +
            "Session was just compacted. The conversation summary above is a hint, NOT a substitute " +
            "for your startup sequence. Run your Session Startup sequence before proceeding.\n\n" +
            "Critical rules from AGENTS.md:\n\n" +
            "%s\n\n" +
            "Current time: %s";

    private PromptsConfig() {
        // 工具类，禁止实例化
    }
}
