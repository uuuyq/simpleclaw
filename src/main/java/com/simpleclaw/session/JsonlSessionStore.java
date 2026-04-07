package com.simpleclaw.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleclaw.session.model.SessionEntry;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * 【JSONL Session Store】
 * 
 * 实现基于 JSONL 格式的会话存储，支持 Append-Only 全量保留策略。
 * 
 * 核心功能：
 * 1. 逐行追加写入（原子操作）
 * 2. 高效读取（支持从指定 ID 开始读取）
 * 3. 查找最后一个 compaction entry
 * 4. 构建会话上下文（注入摘要 + 保留消息）
 */
@Slf4j
public class JsonlSessionStore {
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    private final Path sessionFile;
    
    /**
     * 构造函数
     * 
     * @param sessionFile JSONL 文件路径
     */
    public JsonlSessionStore(Path sessionFile) {
        this.sessionFile = sessionFile;
        
        // 确保父目录存在
        try {
            Files.createDirectories(sessionFile.getParent());
        } catch (IOException e) {
            log.error("Failed to create session directory: {}", sessionFile.getParent(), e);
        }
    }
    
    /**
     * 【检查会话文件是否存在】
     *
     * @return 如果文件存在返回 true
     */
    public boolean exists() {
        return Files.exists(sessionFile);
    }

    /**
     * 【追加 Entry 到 JSONL 文件】
     *
     * 使用原子追加操作，确保并发安全。
     *
     * @param entry 要追加的 Entry
     */
    public void appendEntry(SessionEntry entry) {
        try {
            String jsonLine = MAPPER.writeValueAsString(entry);
            
            Files.writeString(
                sessionFile,
                jsonLine + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            
            log.debug("[JsonlSession] Appended entry: type={}, id={}", entry.getType(), entry.getId());
            
        } catch (IOException e) {
            log.error("[JsonlSession] Failed to append entry: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to append session entry", e);
        }
    }
    
    /**
     * 【追加消息 Entry】
     *
     * 便捷方法，用于追加对话消息。
     *
     * @param role 角色（user/assistant/system）
     * @param content 内容
     * @return 创建的 Entry ID
     */
    /**
     * 【追加消息 Entry】
     *
     * @param role 角色（user/assistant/system）
     * @param content 内容
     * @return 创建的 Entry ID
     */
    public String appendMessage(String role, String content) {
        SessionEntry entry = SessionEntry.builder()
            .type(SessionEntry.EntryType.MESSAGE)
            .role(role)
            .content(content)
            .timestamp(Instant.now().toString())
            .build();

        appendEntry(entry);
        return entry.getId();
    }

    /**
     * 【追加消息 Entry（带 totalTokens）】
     *
     * @param role 角色（user/assistant/system）
     * @param content 内容
     * @param totalTokens 累计的总 Token 数
     * @return 创建的 Entry ID
     */
    public String appendMessage(String role, String content, Integer totalTokens) {
        SessionEntry entry = SessionEntry.builder()
            .type(SessionEntry.EntryType.MESSAGE)
            .role(role)
            .content(content)
            .totalTokens(totalTokens)
            .timestamp(Instant.now().toString())
            .build();

        appendEntry(entry);
        return entry.getId();
    }
    
    /**
     * 【追加压缩标记 Entry】
     *
     * @param summary 压缩摘要（5-section 结构化）
     * @param firstKeptEntryId 第一个保留的消息 ID
     * @param totalTokens 压缩后的总 Token 数（header + 摘要）
     * @return 创建的 Entry ID
     */
    public String appendCompaction(
            String summary,
            String firstKeptEntryId,
            Integer totalTokens) {

        SessionEntry entry = SessionEntry.builder()
            .type(SessionEntry.EntryType.COMPACTION)
            .summary(summary)
            .firstKeptEntryId(firstKeptEntryId)
            .totalTokens(totalTokens)
            .timestamp(Instant.now().toString())
            .build();

        appendEntry(entry);
        log.info("[JsonlSession] Appended compaction: id={}, totalTokens={}",
                 entry.getId(), totalTokens);

        return entry.getId();
    }
    
    /**
     * 【读取所有 Entries】
     * 
     * @return 所有 Entry 列表（按时间顺序）
     */
    public List<SessionEntry> readAllEntries() {
        if (!Files.exists(sessionFile)) {
            return Collections.emptyList();
        }
        
        List<SessionEntry> entries = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                
                try {
                    SessionEntry entry = MAPPER.readValue(line, SessionEntry.class);
                    entries.add(entry);
                } catch (Exception e) {
                    log.warn("[JsonlSession] Failed to parse line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[JsonlSession] Failed to read session file: {}", e.getMessage(), e);
        }
        
        return entries;
    }
    
    /**
     * 【读取文件头】
     *
     * @return 第一个 header entry，如果没有则返回 null
     */
    public SessionEntry readHeader() {
        List<SessionEntry> entries = readAllEntries();

        for (SessionEntry entry : entries) {
            if (entry.getType() == SessionEntry.EntryType.HEADER) {
                return entry;
            }
        }

        return null;
    }

}
