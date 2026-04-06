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
     * 【追加压缩标记 Entry】
     * 
     * @param summary 压缩摘要（5-section 结构化）
     * @param firstKeptEntryId 第一个保留的消息 ID
     * @param tokensBefore 压缩前 Token 数
     * @param tokensAfter 压缩后 Token 数
     * @return 创建的 Entry ID
     */
    public String appendCompaction(
            String summary,
            String firstKeptEntryId,
            int tokensBefore,
            int tokensAfter) {
        
        SessionEntry entry = SessionEntry.builder()
            .type(SessionEntry.EntryType.COMPACTION)
            .summary(summary)
            .firstKeptEntryId(firstKeptEntryId)
            .tokensBefore(tokensBefore)
            .tokensAfter(tokensAfter)
            .timestamp(Instant.now().toString())
            .build();
        
        appendEntry(entry);
        log.info("[JsonlSession] Appended compaction: id={}, tokens {}->{}", 
                 entry.getId(), tokensBefore, tokensAfter);
        
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

    /**
     * 【查找最后一个压缩标记】
     *
     * @return 最后一个 compaction entry，如果没有则返回 null
     */
    public SessionEntry findLastCompaction() {
        List<SessionEntry> entries = readAllEntries();

        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).isCompaction()) {
                return entries.get(i);
            }
        }

        return null;
    }
    
    /**
     * 【构建会话上下文】
     *
     * 根据最后一个有效的 compaction entry 构建发送给 LLM 的消息列表：
     * 1. 查找最后一个有效的 compaction（firstKeptEntryId 指向存在的消息）
     * 2. 如果有有效 compaction，注入摘要 + 从 firstKeptEntryId 开始的消息
     * 3. 如果没有有效 compaction，返回所有消息
     *
     * @return LLM 消息列表
     */
    public List<Map<String, Object>> buildContext() {
        List<SessionEntry> allEntries = readAllEntries();

        if (allEntries.isEmpty()) {
            return Collections.emptyList();
        }

        // 【查找最后一个有效的 compaction】
        // 从后向前扫描，找到第一个有效的 compaction
        // 有效的条件：firstKeptEntryId 为 null（全部压缩）或指向存在的消息
        SessionEntry validCompaction = null;
        for (int i = allEntries.size() - 1; i >= 0; i--) {
            SessionEntry entry = allEntries.get(i);
            if (entry.isCompaction()) {
                String firstKeptId = entry.getFirstKeptEntryId();
                // firstKeptEntryId 为 null 表示全部压缩，也是有效的
                // 不为 null 时需要检查是否存在
                if (firstKeptId == null) {
                    validCompaction = entry;
                    break;
                }
                boolean idExists = allEntries.stream()
                        .anyMatch(e -> e.getId().equals(firstKeptId));
                if (idExists) {
                    validCompaction = entry;
                    break;
                } else {
                    log.debug("[JsonlSession] Skipping invalid compaction: id={}, firstKeptEntryId={} not found",
                            entry.getId(), firstKeptId);
                }
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();

        if (validCompaction != null) {
            // 【注入压缩摘要】
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "[Previous Session Summary]\n\n" + validCompaction.getSummary());
            messages.add(summaryMsg);

            // 【从 firstKeptEntryId 开始读取消息】
            String firstKeptId = validCompaction.getFirstKeptEntryId();

            // firstKeptEntryId 为 null 表示全部压缩，只返回摘要
            if (firstKeptId != null) {
                boolean found = false;
                for (SessionEntry entry : allEntries) {
                    if (entry.getId().equals(firstKeptId)) {
                        found = true;
                    }
                    if (found && entry.isMessage()) {
                        messages.add(entry.toMessageMap());
                    }
                }
            }

            log.info("[JsonlSession] Built context from compaction: id={}, firstKeptEntryId={}, messages={}",
                    validCompaction.getId(), firstKeptId, messages.size() - 1); // -1 因为第一条是摘要

        } else {
            // 【没有有效 compaction，返回所有消息】
            for (SessionEntry entry : allEntries) {
                if (entry.isMessage()) {
                    messages.add(entry.toMessageMap());
                }
            }

            log.info("[JsonlSession] Built context with all messages: count={}", messages.size());
        }

        return messages;
    }
    
    /**
     * 【获取所有消息 Entries】
     * 
     * @return 所有 MESSAGE 类型的 Entry
     */
    public List<SessionEntry> getAllMessages() {
        return readAllEntries().stream()
            .filter(SessionEntry::isMessage)
            .toList();
    }
    
    /**
     * 【统计 Token 使用量】
     * 
     * @return 总 Token 数
     */
    public int getTotalTokens() {
        return readAllEntries().stream()
            .filter(e -> e.getUsage() != null)
            .mapToInt(e -> e.getUsage().getTotalTokens())
            .sum();
    }
    
    /**
     * 【获取文件大小（字节）】
     */
    public long getFileSize() {
        try {
            return Files.size(sessionFile);
        } catch (IOException e) {
            log.warn("[JsonlSession] Failed to get file size: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * 【检查文件是否存在】
     */
    public boolean exists() {
        return Files.exists(sessionFile);
    }
    
    /**
     * 【获取会话文件路径】
     */
    public Path getSessionFile() {
        return sessionFile;
    }
}
