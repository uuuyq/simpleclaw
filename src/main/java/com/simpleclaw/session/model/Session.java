package com.simpleclaw.session.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 【会话】
 *
 * 轻量级会话对象，只保留会话标识和元数据。
 * 消息数据存储在 JSONL 文件中，不保存在内存中。
 *
 * 【设计原则】：
 * - JSONL 文件是真相源，Session 对象只是引用
 * - 消息通过 SessionManager 操作，自动同步到 JSONL
 * - 读取消息时从 JsonlSessionStore 实时构建
 */
public class Session {

    /** 会话唯一标识，格式通常为 "{channel}:{chatId}" */
    private String key;

    /** 会话创建时间 */
    private Instant createdAt;

    /** 会话最后更新时间 */
    private Instant updatedAt;

    /** 会话元数据，可存储渠道特定信息 */
    private Map<String, Object> metadata;

    /**
     * 默认构造函数，用于反序列化
     */
    public Session() {
    }

    /**
     * 创建新会话
     * @param key 会话唯一标识，格式为 "{channel}:{chatId}"
     */
    public Session(String key) {
        this.key = key;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.metadata = Collections.emptyMap();
    }

    /**
     * 更新最后访问时间
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
