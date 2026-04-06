package com.simpleclaw.session.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 【JSONL Session Entry】
 * 
 * 表示会话文件中的一行记录，支持多种类型。
 * 用于实现 Append-Only 的全量历史保留策略。
 * 
 * Entry 类型：
 * - header: 文件头，记录会话元数据
 * - message: 对话消息（user/assistant/system）
 * - compaction: 压缩标记（含摘要 + firstKeptEntryId）
 * - model_change: 模型切换事件
 * - session_info: 会话信息更新
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionEntry {
    
    /**
     * Entry 类型枚举
     */
    public enum EntryType {
        @JsonProperty("header") HEADER,
        @JsonProperty("message") MESSAGE,
        @JsonProperty("compaction") COMPACTION,
        @JsonProperty("model_change") MODEL_CHANGE,
        @JsonProperty("session_info") SESSION_INFO
    }
    
    /** Entry 类型（必需） */
    private EntryType type;
    
    /** 唯一标识符（UUID） */
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    
    /** 时间戳（ISO 8601 格式） */
    @Builder.Default
    private String timestamp = Instant.now().toString();
    
    // ========== Message 类型字段 ==========
    
    /** 消息角色（user/assistant/system） */
    private String role;
    
    /** 消息内容 */
    private String content;
    
    /** Token 使用量统计 */
    private TokenUsage usage;
    
    /** 工具调用列表 */
    private List<Map<String, Object>> toolCalls;
    
    /** 工具调用结果 */
    private Map<String, Object> toolCallResult;
    
    // ========== Compaction 类型字段 ==========
    
    /** 压缩摘要文本（5-section 结构化） */
    private String summary;
    
    /** 第一个保留的消息 ID（压缩后从此处开始保留原始消息） */
    private String firstKeptEntryId;
    
    /** 压缩前的 Token 数 */
    private Integer tokensBefore;
    
    /** 压缩后的 Token 数（摘要 + 保留消息） */
    private Integer tokensAfter;
    
    /** 压缩详情（读取/修改的文件等） */
    private CompactionDetails details;
    
    // ========== Model Change 类型字段 ==========
    
    /** 旧模型名称 */
    private String oldModel;
    
    /** 新模型名称 */
    private String newModel;
    
    // ========== Session Info 类型字段 ==========
    
    /** 会话元数据 */
    private Map<String, Object> metadata;
    
    /**
     * Token 使用量统计
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
    
    /**
     * 压缩详情
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompactionDetails {
        /** 本次压缩范围内读取的文件列表 */
        private List<String> readFiles;
        
        /** 本次压缩范围内修改的文件列表 */
        private List<String> modifiedFiles;
        
        /** 工具调用失败记录 */
        private List<ToolFailure> toolFailures;
    }
    
    /**
     * 工具调用失败记录
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolFailure {
        private String toolName;
        private String status;
        private String errorMessage;
    }
    
    /**
     * 判断是否为消息类型
     */
    public boolean isMessage() {
        return type == EntryType.MESSAGE;
    }
    
    /**
     * 判断是否为压缩标记
     */
    public boolean isCompaction() {
        return type == EntryType.COMPACTION;
    }
    
    /**
     * 转换为 LLM 消息格式（仅 MESSAGE 类型）
     */
    public Map<String, Object> toMessageMap() {
        if (type != EntryType.MESSAGE) {
            throw new IllegalStateException("Cannot convert non-message entry to message map");
        }
        
        Map<String, Object> message = new java.util.HashMap<>();
        message.put("role", role);
        message.put("content", content);
        
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }
        
        if (usage != null) {
            message.put("usage", Map.of(
                "prompt_tokens", usage.getPromptTokens(),
                "completion_tokens", usage.getCompletionTokens(),
                "total_tokens", usage.getTotalTokens()
            ));
        }
        
        return message;
    }
}
