package com.simpleclaw.providers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LLM 对话补全响应：content、toolCalls、finishReason、usage、reasoningContent。
 */
public class LLMResponse {

    private String content;
    private List<ToolCallRequest> toolCalls;
    private String finishReason = "stop";
    private Map<String, Integer> usage;
    private String reasoningContent;

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCallRequest> getToolCalls() {
        return toolCalls == null ? Collections.<ToolCallRequest>emptyList() : toolCalls;
    }

    public void setToolCalls(List<ToolCallRequest> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Map<String, Integer> getUsage() {
        return usage == null ? Collections.<String, Integer>emptyMap() : usage;
    }

    public void setUsage(Map<String, Integer> usage) {
        this.usage = usage;
    }

    /**
     * 【获取 prompt tokens】
     * @return prompt_tokens，如果没有则返回 0
     */
    public int getPromptTokens() {
        return usage != null && usage.containsKey("prompt_tokens") ? usage.get("prompt_tokens") : 0;
    }

    /**
     * 【获取 completion tokens】
     * @return completion_tokens，如果没有则返回 0
     */
    public int getCompletionTokens() {
        return usage != null && usage.containsKey("completion_tokens") ? usage.get("completion_tokens") : 0;
    }

    /**
     * 【获取 total tokens】
     * @return total_tokens，如果没有则返回 0
     */
    public int getTotalTokens() {
        return usage != null && usage.containsKey("total_tokens") ? usage.get("total_tokens") : 0;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }
}
