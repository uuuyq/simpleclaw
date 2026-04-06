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
 * 2. 消息的追加（自动同步到 JSONL）
 * 3. 上下文的构建（从 JSONL 读取）
 *
 * 【设计原则】：
 * - JSONL 文件是真相源
 * - Session 对象只保留元数据，不保存消息
 * - 所有消息操作通过此管理器，自动同步到文件
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
     * 从 JSONL 文件加载会话元数据
     */
    private Session loadExistingSession(String key) {
        Session session = new Session(key);

        // 从 JSONL 读取 header 获取元数据
        JsonlSessionStore store = getJsonlStore(key);
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

        log.info("[SessionManager] 加载已有会话: {}", key);
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

        // 更新会话的访问时间
        Session session = cache.get(sessionKey);
        if (session != null) {
            session.touch();
        }

        return id;
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
