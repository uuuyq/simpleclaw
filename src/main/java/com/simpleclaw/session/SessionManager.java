package com.simpleclaw.session;

import com.simpleclaw.session.model.Session;
import com.simpleclaw.session.model.SessionEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【会话管理器】
 *
 * 统一管理层，负责：
 * 1. 会话的获取、创建、缓存
 * 2. Entry 的内存缓存和持久化（JSONL）
 * 3. 上下文的构建（优先从内存读取）
 *
 * 【设计原则】：
 * - Session 对象内存中缓存所有 entry
 * - JSONL 文件作为持久化备份
 * - 读取 entry 优先从内存获取，内存未命中则从 JSONL 加载
 */
@Slf4j
public class SessionManager {

    /** 会话文件存放目录 */
    private final Path sessionsDir;

    /** 内存缓存：key -> Session */
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    /** JSONL Store 缓存：key -> JsonlSessionStore */
    private final Map<String, JsonlSessionStore> storeCache = new ConcurrentHashMap<>();

    /**
     * 构造函数
     * @param workspace 工作空间目录，会话文件存放在 workspace/sessions/
     */
    public SessionManager(Path workspace) {
        this.sessionsDir = workspace.resolve("sessions");
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            log.error("[SessionManager] 创建会话目录失败: {}", e.getMessage());
        }
    }

    /**
     * 【获取或创建会话】
     *
     * @param key 会话唯一标识，格式为 "{channel}:{chatId}"
     * @return 会话对象
     */
    public Session getOrCreate(String key) {
        Session session = cache.get(key);
        if (session != null) {
            session.touch();
            return session;
        }

        synchronized (this) {
            session = cache.get(key);
            if (session != null) {
                session.touch();
                return session;
            }

            // 检查 JSONL 文件是否存在
            JsonlSessionStore store = getJsonlStore(key);
            if (store.exists()) {
                session = loadExistingSession(key);
            } else {
                session = createNewSession(key);
            }

            cache.put(key, session);
            return session;
        }
    }

    /**
     * 【加载已有会话】
     *
     * 从 JSONL 文件加载会话元数据和所有 entry
     */
    private Session loadExistingSession(String key) {
        Session session = new Session(key);

        // 从 JSONL 读取所有 entry
        JsonlSessionStore store = getJsonlStore(key);
        List<SessionEntry> allEntries = store.readAllEntries();
        session.setEntries(allEntries);

        // 从 header 获取元数据
        SessionEntry header = store.readHeader();
        if (header != null && header.getMetadata() != null) {
            session.setMetadata(header.getMetadata());
            // 如果 header 中有创建时间，使用它
            if (header.getMetadata().containsKey("created_at")) {
                try {
                    session.setCreatedAt(java.time.Instant.parse(
                            header.getMetadata().get("created_at").toString()));
                } catch (Exception e) {
                    // 解析失败则使用当前时间
                }
            }
        }

        log.info("[SessionManager] 加载已有会话: {} ({} entries)", key, allEntries.size());
        return session;
    }

    /**
     * 【创建新会话】
     */
    private Session createNewSession(String key) {
        Session session = new Session(key);

        // 写入 header
        JsonlSessionStore store = getJsonlStore(key);
        SessionEntry header = SessionEntry.builder()
            .type(SessionEntry.EntryType.HEADER)
            .metadata(Map.of("created_at", Instant.now().toString()))
            .build();
        store.appendEntry(header);
        session.addEntry(header);

        log.info("[SessionManager] 创建新会话: {}", key);
        return session;
    }

    /**
     * 【保存会话】
     *
     * 仅更新内存缓存，消息已通过 appendMessage 自动写入 JSONL
     *
     * @param session 要保存的会话
     */
    public void save(Session session) {
        if (session == null || session.getKey() == null) {
            return;
        }
        session.touch();
        cache.put(session.getKey(), session);
        log.debug("[SessionManager] 保存会话到缓存: {}", session.getKey());
    }
    
    /**
     * 【追加消息到 JSONL】
     *
     * @param sessionKey 会话键
     * @param role 角色（user/assistant/system）
     * @param content 内容
     * @return 消息 ID
     */
    public String appendMessage(String sessionKey, String role, String content) {
        JsonlSessionStore store = getJsonlStore(sessionKey);
        String id = store.appendMessage(role, content);

        // 同时添加到内存缓存
        Session session = cache.get(sessionKey);
        if (session != null) {
            session.touch();
            SessionEntry entry = SessionEntry.builder()
                .type(SessionEntry.EntryType.MESSAGE)
                .role(role)
                .content(content)
                .timestamp(Instant.now().toString())
                .build();
            session.addEntry(entry);
        }

        return id;
    }

    /**
     * 【追加消息到会话（带 totalTokens）】
     *
     * @param sessionKey 会话键
     * @param role 角色（user/assistant/system）
     * @param content 内容
     * @param totalTokens 累计的总 Token 数
     * @return 消息 ID
     */
    public String appendMessage(String sessionKey, String role, String content,
                                Integer totalTokens) {
        JsonlSessionStore store = getJsonlStore(sessionKey);
        String id = store.appendMessage(role, content, totalTokens);

        // 同时添加到内存缓存
        Session session = cache.get(sessionKey);
        if (session != null) {
            session.touch();
            SessionEntry entry = SessionEntry.builder()
                .type(SessionEntry.EntryType.MESSAGE)
                .role(role)
                .content(content)
                .totalTokens(totalTokens)
                .timestamp(Instant.now().toString())
                .build();
            session.addEntry(entry);
        }

        return id;
    }

    /**
     * 【追加压缩标记 Entry】
     *
     * 同时写入 JSONL 和内存缓存
     *
     * @param sessionKey 会话键
     * @param summary 压缩摘要
     * @param firstKeptEntryId 第一个保留的消息 ID
     * @param totalTokens 压缩后的总 Token 数（header + 摘要）
     * @return Entry ID
     */
    public String appendCompaction(String sessionKey, String summary,
                                   String firstKeptEntryId, Integer totalTokens) {
        JsonlSessionStore store = getJsonlStore(sessionKey);
        String id = store.appendCompaction(summary, firstKeptEntryId, totalTokens);

        // 同时添加到内存缓存
        Session session = cache.get(sessionKey);
        if (session != null) {
            session.touch();
            SessionEntry entry = SessionEntry.builder()
                .id(id)  // 使用 JSONL 返回的 ID
                .type(SessionEntry.EntryType.COMPACTION)
                .summary(summary)
                .firstKeptEntryId(firstKeptEntryId)
                .totalTokens(totalTokens)
                .timestamp(Instant.now().toString())
                .build();
            session.addEntry(entry);
        }

        return id;
    }

    /**
     * 【获取会话的所有 Entry】
     *
     * 优先从内存获取，如果内存为空则从 JSONL 加载
     *
     * @param sessionKey 会话键
     * @return Entry 列表
     */
    public List<SessionEntry> getAllEntries(String sessionKey) {
        Session session = cache.get(sessionKey);
        if (session != null && session.getEntryCount() > 0) {
            return session.getEntries();
        }

        // 内存未命中，从 JSONL 加载
        JsonlSessionStore store = getJsonlStore(sessionKey);
        return store.readAllEntries();
    }

    /**
     * 【重新加载会话 Entry】
     *
     * 用于压缩后刷新内存中的 entry
     *
     * @param sessionKey 会话键
     */
    public void reloadEntries(String sessionKey) {
        Session session = cache.get(sessionKey);
        if (session == null) {
            return;
        }

        JsonlSessionStore store = getJsonlStore(sessionKey);
        List<SessionEntry> allEntries = store.readAllEntries();
        session.setEntries(allEntries);
        log.info("[SessionManager] 重新加载会话 entry: {} ({} entries)", sessionKey, allEntries.size());
    }


    /**
     * 【获取 JsonlSessionStore】
     * 
     * @param sessionKey 会话键
     * @return JsonlSessionStore 实例
     */
    public JsonlSessionStore getJsonlStore(String sessionKey) {
        return storeCache.computeIfAbsent(sessionKey, k -> {
            Path path = getSessionPath(k);
            return new JsonlSessionStore(path);
        });
    }
    
    /**
     * 【获取会话文件路径】
     */
    private Path getSessionPath(String key) {
        String safe = sanitizeKey(key);
        return sessionsDir.resolve(safe + ".jsonl");
    }
    
    /**
     * 【清理会话标识】
     */
    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
