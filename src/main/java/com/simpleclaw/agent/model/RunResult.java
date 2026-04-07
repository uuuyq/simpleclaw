package com.simpleclaw.agent.model;

import java.util.Collections;
import java.util.List;

/**
 * runAgentLoop 的返回：最终回复文本、使用过的工具名列表、Token 使用量。
 */
public class RunResult {

    private final String content;
    private final List<String> toolsUsed;
    private final Integer promptTokens;      // 第一次 chat 的 prompt_tokens
    private final Integer completionTokens;  // 最后一次 chat 的 completion_tokens

    public RunResult(String content, List<String> toolsUsed) {
        this(content, toolsUsed, null, null);
    }

    public RunResult(String content, List<String> toolsUsed, Integer promptTokens, Integer completionTokens) {
        this.content = content != null ? content : "";
        this.toolsUsed = toolsUsed != null ? toolsUsed : Collections.<String>emptyList();
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public String getContent() {
        return content;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 【计算总的 Prompt Tokens】
     * 用于保存到 SessionEntry：promptTokens + completionTokens
     */
    public Integer getTotalPromptTokens() {
        if (promptTokens == null || completionTokens == null) {
            return null;
        }
        return promptTokens + completionTokens;
    }
}
