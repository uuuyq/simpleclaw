package com.simpleclaw.session.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 【会话】
 *
 * 会话对象，保存会话标识、元数据和内存中的 entry 列表。
 * Entry 列表有内存大小限制，超出时自动丢弃最前面的 entry。
 * JSONL 文件仍作为持久化存储。
 *
 * 【设计原则】：
 * - 内存中缓存最近的所有 entry，避免频繁磁盘 I/O
 * - JSONL 文件作为持久化备份
 * - 读取 entry 优先从内存获取
 */
public class Session {

    /** 默认内存中最大 entry 数量 */
    private static final int DEFAULT_MAX_ENTRIES_IN_MEMORY = 1000;

    /** 会话唯一标识，格式通常为 "{channel}:{chatId}" */
    private String key;

    /** 会话创建时间 */
    private Instant createdAt;

    /** 会话最后更新时间 */
    private Instant updatedAt;

    /** 会话元数据，可存储渠道特定信息 */
    private Map<String, Object> metadata;

    /** 内存中的 entry 列表（按时间顺序） */
    private final List<SessionEntry> entries;

    /** 内存中最大 entry 数量 */
    private final int maxEntriesInMemory;

    /**
     * 默认构造函数，用于反序列化
     */
    public Session() {
        this.entries = new ArrayList<>();
        this.maxEntriesInMemory = DEFAULT_MAX_ENTRIES_IN_MEMORY;
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
        this.entries = new ArrayList<>();
        this.maxEntriesInMemory = DEFAULT_MAX_ENTRIES_IN_MEMORY;
    }

    /**
     * 创建新会话（指定内存限制）
     * @param key 会话唯一标识
     * @param maxEntriesInMemory 内存中最大 entry 数量
     */
    public Session(String key, int maxEntriesInMemory) {
        this.key = key;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.metadata = Collections.emptyMap();
        this.entries = new ArrayList<>();
        this.maxEntriesInMemory = maxEntriesInMemory;
    }

    /**
     * 更新最后访问时间
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * 【添加 Entry 到内存】
     * 如果超出内存限制，丢弃最前面的 entry
     */
    public void addEntry(SessionEntry entry) {
        entries.add(entry);
        // 如果超出限制，丢弃最前面的 entry
        while (entries.size() > maxEntriesInMemory) {
            entries.remove(0);
        }
    }

    /**
     * 【获取所有 Entry（内存中）】
     */
    public List<SessionEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * 【获取 Entry 数量】
     */
    public int getEntryCount() {
        return entries.size();
    }

    /**
     * 【清空内存中的 Entry】
     * 用于压缩后重新加载
     */
    public void clearEntries() {
        entries.clear();
    }

    /**
     * 【批量设置 Entry】
     * 用于从 JSONL 加载后初始化
     */
    public void setEntries(List<SessionEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        // 如果超出限制，只保留最近的
        while (entries.size() > maxEntriesInMemory) {
            entries.remove(0);
        }
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
