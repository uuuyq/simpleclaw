package com.simpleclaw.memory;

import com.simpleclaw.memory.model.MemoryChunk;
import com.simpleclaw.providers.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * 【向量记忆存储】
 *
 * 基于 SQLite 的向量记忆存储系统，支持：
 * 1. 文档分块索引
 * 2. 向量相似度搜索
 * 3. 全文关键词搜索（FTS）
 * 4. 混合检索（向量 + 关键词加权）
 *
 * 参考 OpenClaw 实现：
 * - 使用 sqlite-vec 扩展进行向量存储
 * - 使用 FTS5 进行全文搜索
 * - 混合加权融合策略
 */
@Slf4j
public class VectorMemoryStore {

    // 【数据库连接】
    private final Connection connection;

    // 【Embedding 提供商】
    private final EmbeddingProvider embeddingProvider;
    private final float vectorWeight;
    private final float textWeight;
    private final int candidateMultiplier;

    // 【向量维度】
    private final int dimension;

    // 【数据库路径】
    private final Path dbPath;

    // 【SQL 语句：创建 chunks 表 - 合并分块、向量和嵌入缓存】
    private static final String CREATE_CHUNKS_TABLE =
            "CREATE TABLE IF NOT EXISTS chunks (" +
            "id TEXT PRIMARY KEY," +
            "content TEXT NOT NULL," +
            "content_hash TEXT NOT NULL," +  // 分块内容哈希（用于嵌入缓存）
            "vector BLOB," +  // 向量数据（可为空，首次插入时可能还未生成）
            "source TEXT," +
            "source_path TEXT," +
            "timestamp INTEGER," +
            "chunk_index INTEGER," +
            "created_at INTEGER DEFAULT (strftime('%s', 'now'))" +
            ")";

    // 【SQL 语句：创建 FTS 全文搜索表】
    private static final String CREATE_FTS_TABLE =
            "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(" +
            "content, source, content_rowid=rowid" +
            ")";

    // 【SQL 语句：创建文件哈希表（第一层哈希）】
    private static final String CREATE_FILES_TABLE =
            "CREATE TABLE IF NOT EXISTS files (" +
            "path TEXT PRIMARY KEY," +
            "content_hash TEXT NOT NULL," +  // 文件内容哈希
            "size INTEGER," +
            "modified_time INTEGER" +
            ")";

    // 【SQL 语句：创建索引】
    private static final String CREATE_INDEX_SOURCE =
            "CREATE INDEX IF NOT EXISTS idx_chunks_source ON chunks(source)";

    private static final String CREATE_INDEX_TIMESTAMP =
            "CREATE INDEX IF NOT EXISTS idx_chunks_timestamp ON chunks(timestamp)";

    private static final String CREATE_INDEX_CONTENT_HASH =
            "CREATE INDEX IF NOT EXISTS idx_chunks_content_hash ON chunks(content_hash)";

    /**
     * 【构造函数】
     *
     * @param workspace 工作空间路径
     * @param embeddingProvider Embedding 提供商
     * @param config Agent 配置（包含记忆检索参数）
     * @throws SQLException 数据库连接失败
     */
    public VectorMemoryStore(Path workspace, EmbeddingProvider embeddingProvider, com.simpleclaw.config.model.AgentConfig config) throws SQLException {
        this.embeddingProvider = embeddingProvider;
        this.dimension = embeddingProvider.getDimension();
        this.dbPath = workspace.resolve("memory/vector_memory.db");
        this.vectorWeight = config.getVectorWeight();
        this.textWeight = config.getTextWeight();
        this.candidateMultiplier = config.getCandidateMultiplier();

        // 【确保目录存在】
        dbPath.getParent().toFile().mkdirs();

        // 【连接数据库】
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        this.connection = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toString(),
                sqliteConfig.toProperties()
        );

        // 【初始化表结构】
        initializeTables();

        log.info("[VectorMemory] 初始化完成，维度: {}, 路径: {}", dimension, dbPath);
    }

    /**
     * 【初始化数据库表】
     */
    private void initializeTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 创建 chunks 表（合并分块、向量和嵌入缓存）
            stmt.execute(CREATE_CHUNKS_TABLE);
            // 创建 FTS 表
            stmt.execute(CREATE_FTS_TABLE);
            // 创建文件哈希表（第一层哈希）
            stmt.execute(CREATE_FILES_TABLE);
            // 创建索引
            stmt.execute(CREATE_INDEX_SOURCE);
            stmt.execute(CREATE_INDEX_TIMESTAMP);
            stmt.execute(CREATE_INDEX_CONTENT_HASH);
        }
        log.debug("[VectorMemory] 数据库表初始化完成");
    }

    /**
     * 【添加记忆块】
     *
     * @param chunk 记忆块
     */
    public void addChunk(MemoryChunk chunk) throws SQLException {
        // 【如果没有向量，先计算】
        if (chunk.getEmbedding() == null && chunk.getContent() != null) {
            chunk.setEmbedding(embeddingProvider.embed(chunk.getContent()));
        }

        // 【插入 chunks 表（合并内容和向量）】
        String insertChunk =
                "INSERT OR REPLACE INTO chunks (id, content, content_hash, vector, source, source_path, timestamp, chunk_index) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(insertChunk)) {
            pstmt.setString(1, chunk.getId());
            pstmt.setString(2, chunk.getContent());
            pstmt.setString(3, chunk.getContentHash());
            pstmt.setBytes(4, floatArrayToBytes(chunk.getEmbedding()));
            pstmt.setString(5, chunk.getSource());
            pstmt.setString(6, chunk.getSourcePath());
            pstmt.setLong(7, chunk.getTimestamp());
            pstmt.setInt(8, chunk.getChunkIndex());
            pstmt.executeUpdate();
        }

        // 【插入 FTS 表（用于全文搜索）】
        String insertFts =
                "INSERT OR REPLACE INTO memory_fts (rowid, content, source) " +
                "SELECT rowid, content, source FROM chunks WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(insertFts)) {
            pstmt.setString(1, chunk.getId());
            pstmt.executeUpdate();
        }

        log.debug("[VectorMemory] 添加记忆块: {}", chunk.getId());
    }

    // ==================== 两层哈希方法 ====================

    /**
     * 【第一层哈希：获取文件内容哈希】
     *
     * @param path 文件路径
     * @return 文件内容哈希，如果不存在返回 null
     */
    public String getFileContentHash(String path) throws SQLException {
        String sql = "SELECT content_hash FROM files WHERE path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, path);
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content_hash");
                }
            }
        }
        return null;
    }

    /**
     * 【第一层哈希：更新文件内容哈希】
     *
     * @param path 文件路径
     * @param contentHash 文件内容哈希
     * @param size 文件大小
     */
    public void updateFileContentHash(String path, String contentHash, long size) throws SQLException {
        String sql = "INSERT OR REPLACE INTO files (path, content_hash, size, modified_time) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, path);
            pstmt.setString(2, contentHash);
            pstmt.setLong(3, size);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
        }
    }

    /**
     * 【从 chunks 表获取嵌入向量】
     *
     * @param contentHash 分块内容哈希
     * @return 嵌入向量，如果不存在返回 null
     */
    public float[] getCachedEmbedding(String contentHash) throws SQLException {
        String sql = "SELECT vector FROM chunks WHERE content_hash = ? AND vector IS NOT NULL";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, contentHash);
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return bytesToFloatArray(rs.getBytes("vector"));
                }
            }
        }
        return null;
    }

    /**
     * 【更新 chunks 表的嵌入向量】
     *
     * @param contentHash 分块内容哈希
     * @param embedding 嵌入向量
     */
    public void cacheEmbedding(String contentHash, float[] embedding) throws SQLException {
        String sql = "UPDATE chunks SET vector = ? WHERE content_hash = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setBytes(1, floatArrayToBytes(embedding));
            pstmt.setString(2, contentHash);
            pstmt.executeUpdate();
        }
    }

    /**
     * 【批量获取嵌入向量】
     *
     * @param contentHashes 分块内容哈希列表
     * @return 哈希到向量的映射
     */
    public Map<String, float[]> getCachedEmbeddingsBatch(List<String> contentHashes) throws SQLException {
        Map<String, float[]> result = new HashMap<>();
        if (contentHashes.isEmpty()) {
            return result;
        }

        // 构建 IN 子句
        String placeholders = String.join(",", Collections.nCopies(contentHashes.size(), "?"));
        String sql = "SELECT content_hash, vector FROM chunks WHERE content_hash IN (" + placeholders + ") AND vector IS NOT NULL";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            int idx = 1;
            for (String hash : contentHashes) {
                pstmt.setString(idx++, hash);
            }

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("content_hash"), bytesToFloatArray(rs.getBytes("vector")));
                }
            }
        }
        return result;
    }

    // ==================== 批量添加记忆块 ====================

    /**
     * 【批量添加记忆块】
     */
    public void addChunks(List<MemoryChunk> chunks) throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (MemoryChunk chunk : chunks) {
                addChunk(chunk);
            }
            connection.commit();
            log.info("[VectorMemory] 批量添加 {} 个记忆块", chunks.size());
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * 【向量相似度搜索】
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 按相似度排序的记忆块列表
     */
    public List<MemoryChunk> vectorSearch(String query, int topK) throws SQLException {
        // 【计算查询向量】
        float[] queryVector = embeddingProvider.embed(query);

        // 【获取所有向量并计算相似度】
        // 注意：这是简化实现，大数据量时应使用向量索引（如 sqlite-vec 扩展）
        String selectAll =
                "SELECT id, content, content_hash, vector, source, source_path, timestamp, chunk_index FROM chunks";

        List<MemoryChunk> results = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(selectAll)) {

            while (rs.next()) {
                MemoryChunk chunk = resultSetToChunk(rs);
                float[] vector = bytesToFloatArray(rs.getBytes("vector"));
                chunk.setEmbedding(vector);

                // 计算余弦相似度
                float similarity = cosineSimilarity(queryVector, vector);
                chunk.setScore(similarity);

                results.add(chunk);
            }
        }

        // 【按相似度排序并取 topK】
        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 【全文关键词搜索 - FTS5 + BM25 排名】
     *
     * 使用 SQLite FTS5 的内置 BM25 算法进行全文搜索
     * FTS5 默认使用 BM25 变体作为排名函数
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 按 BM25 相关性排序的记忆块列表
     */
    public List<MemoryChunk> textSearch(String query, int topK) throws SQLException {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // 【构建健壮的 FTS 查询】
        String ftsQuery = buildFtsQuery(query);
        if (ftsQuery.isEmpty()) {
            return new ArrayList<>();
        }

        // 【使用 FTS5 的 bm25() 函数获取标准 BM25 分数】
        // bm25() 返回负值，绝对值越小越相关
        String selectFts =
                "SELECT c.*, bm25(memory_fts) as bm25_score FROM chunks c " +
                "JOIN memory_fts fts ON c.rowid = fts.rowid " +
                "WHERE memory_fts MATCH ? " +
                "ORDER BY bm25_score ASC " +  // BM25 分数越小（越负）越相关
                "LIMIT ?";

        List<MemoryChunk> results = new ArrayList<>();

        try (PreparedStatement pstmt = connection.prepareStatement(selectFts)) {
            pstmt.setString(1, ftsQuery);
            pstmt.setInt(2, topK);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    MemoryChunk chunk = resultSetToChunk(rs);

                    // 【BM25 分数转换】
                    // FTS5 的 bm25() 返回负值（rank < 0 表示相关）
                    // 转换为 [0,1] 正值：textScore = rank / (1 + rank)
                    // 当 rank < 0 时，结果为正且越接近 1 表示越相关
                    float bm25Score = rs.getFloat("bm25_score");
                    float normalizedScore = bm25Score / (1.0f + bm25Score);

                    chunk.setScore(normalizedScore);
                    results.add(chunk);

                    log.debug("[FTS5] Query='{}', BM25={}, Normalized={}",
                             query.substring(0, Math.min(20, query.length())),
                             bm25Score, normalizedScore);
                }
            }
        }

        log.info("[FTS5] 关键词检索完成: query='{}', results={}", 
                query.substring(0, Math.min(30, query.length())), results.size());
        
        return results;
    }

    /**
     * 【构建 FTS 查询字符串】
     *
     * 策略调整：
     * 1. 分词：CJK 单字 + ASCII 词
     * 2. 用 OR 连接（提高召回率，适合中文检索）
     * 3. 每个 token 用引号包裹
     * 4. 限制最大 token 数量，避免查询过长
     *
     * @param input 原始查询文本
     * @return FTS 查询字符串
     */
    private String buildFtsQuery(String input) {
        // 1. 分词：提取 CJK 单字和 ASCII 词
        List<String> tokens = tokenize(input);

        if (tokens.isEmpty()) {
            return "";
        }

        // 2. 去重并限制 token 数量（避免查询过长）
        List<String> uniqueTokens = tokens.stream()
                .distinct()
                .limit(10)  // 最多取 10 个不同的 token
                .toList();

        // 3. 用 OR 连接，提高召回率
        // 例如："foo" OR "bar"
        // 中文检索中，OR 比 AND 更适合，因为用户可能只记住部分内容
        return uniqueTokens.stream()
                .map(t -> "\"" + t + "\"")
                .reduce((a, b) -> a + " OR " + b)
                .orElse("");
    }

    /**
     * 【分词】
     *
     * 策略：
     * - CJK 字符：提取 2-4 字的连续片段（提高语义相关性）
     * - ASCII：连续字母数字作为 token
     * - 过滤停用词和过短的词
     *
     * @param input 输入文本
     * @return token 列表
     */
    private List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return tokens;
        }

        // 提取 CJK 连续片段（2-4 字）
        List<String> cjkPhrases = extractCjkPhrases(input);
        tokens.addAll(cjkPhrases);

        // 提取 ASCII 词
        StringBuilder currentToken = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                currentToken.append(c);
            } else {
                if (currentToken.length() > 0) {
                    String word = currentToken.toString().toLowerCase();
                    if (word.length() >= 2) {  // 至少 2 个字符
                        tokens.add(word);
                    }
                    currentToken.setLength(0);
                }
            }
        }
        if (currentToken.length() >= 2) {
            tokens.add(currentToken.toString().toLowerCase());
        }

        return tokens;
    }

    /**
     * 【提取 CJK 连续片段】
     *
     * 将连续的 CJK 字符提取为 2-4 字的片段，保留更多语义信息
     *
     * @param input 输入文本
     * @return CJK 片段列表
     */
    private List<String> extractCjkPhrases(String input) {
        List<String> phrases = new ArrayList<>();
        List<Character> cjkBuffer = new ArrayList<>();

        for (char c : input.toCharArray()) {
            if (isCjkCharacter(c)) {
                cjkBuffer.add(c);
            } else {
                // 遇到非 CJK，处理缓冲区
                extractPhrasesFromBuffer(cjkBuffer, phrases);
                cjkBuffer.clear();
            }
        }
        // 处理最后一批
        extractPhrasesFromBuffer(cjkBuffer, phrases);

        return phrases;
    }

    /**
     * 【从 CJK 缓冲区提取片段】
     *
     * 使用滑动窗口提取 2-4 字的片段
     */
    private void extractPhrasesFromBuffer(List<Character> buffer, List<String> phrases) {
        if (buffer.size() < 2) {
            return;
        }

        // 滑动窗口：提取 2-4 字的片段
        for (int len = 2; len <= 4 && len <= buffer.size(); len++) {
            for (int i = 0; i <= buffer.size() - len; i++) {
                StringBuilder phrase = new StringBuilder();
                for (int j = i; j < i + len; j++) {
                    phrase.append(buffer.get(j));
                }
                phrases.add(phrase.toString());
            }
        }
    }

    /**
     * 【判断是否为 CJK 字符】
     */
    private boolean isCjkCharacter(char c) {
        // CJK 统一表意文字范围
        return (c >= '\u4E00' && c <= '\u9FFF') ||   // 基本区
               (c >= '\u3400' && c <= '\u4DBF') ||   // 扩展 A 区
               (c >= '\uF900' && c <= '\uFAFF');     // 兼容区
    }

    /**
     * 【混合检索 - 向量 + FTS5 BM25】
     *
     * 结合向量相似度和 FTS5 BM25 关键词匹配，加权融合结果。
     *
     * 参考 OpenClaw 策略：
     * - 向量权重: 0.7
     * - 关键词权重: 0.3
     * - 候选集扩展: 先取 4 倍结果，再融合取 top
     *
     * @param query 查询文本
     * @param maxResults 最大返回结果数
     * @param minScore 最低相关性分数
     * @return 融合后的记忆块列表
     */
    public List<MemoryChunk> hybridSearch(String query, int maxResults, float minScore)
            throws SQLException {

        // 【参数配置 - 从配置文件读取】
        int candidateCount = maxResults * candidateMultiplier;

        log.info("[Hybrid] 开始混合检索: query='{}', maxResults={}, candidates={}", 
                query.substring(0, Math.min(30, query.length())), maxResults, candidateCount);

        // 【并行执行两种搜索】
        List<MemoryChunk> vectorResults = vectorSearch(query, candidateCount);
        List<MemoryChunk> textResults = textSearch(query, candidateCount);
        
        log.debug("[Hybrid] 向量检索: {} 条, 关键词检索: {} 条", 
                 vectorResults.size(), textResults.size());

        // 【融合结果】
        Map<String, MemoryChunk> merged = new HashMap<>();

        // 【归一化向量搜索结果分数】
        // 向量相似度范围 [0, 1]，直接使用
        for (MemoryChunk chunk : vectorResults) {
            float originalScore = chunk.getScore();
            chunk.setScore(originalScore * vectorWeight);
            merged.put(chunk.getId(), chunk);
            
            log.debug("[Hybrid-Vector] ID={}, Score={:.3f} -> {:.3f}", 
                     chunk.getId(), originalScore, chunk.getScore());
        }

        // 【归一化 BM25 搜索结果分数】
        // BM25 已通过 sigmoid 映射到 [0, 1]
        for (MemoryChunk chunk : textResults) {
            float bm25Score = chunk.getScore();  // 已经是 0-1 范围
            float weightedScore = bm25Score * textWeight;

            if (merged.containsKey(chunk.getId())) {
                // 已在向量结果中，累加分数
                MemoryChunk existing = merged.get(chunk.getId());
                float combinedScore = existing.getScore() + weightedScore;
                existing.setScore(combinedScore);
                
                log.debug("[Hybrid-Merge] ID={}, Vector={:.3f}, BM25={:.3f}, Combined={:.3f}", 
                         chunk.getId(), existing.getScore() - weightedScore, bm25Score, combinedScore);
            } else {
                chunk.setScore(weightedScore);
                merged.put(chunk.getId(), chunk);
                
                log.debug("[Hybrid-BM25] ID={}, BM25={:.3f} -> {:.3f}", 
                         chunk.getId(), bm25Score, weightedScore);
            }
        }

        // 【排序并过滤】
        List<MemoryChunk> results = new ArrayList<>(merged.values());
        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

        // 过滤低分结果
        int beforeFilter = results.size();
        results.removeIf(chunk -> chunk.getScore() < minScore);
        int afterFilter = results.size();
        
        log.info("[Hybrid] 融合完成: 合并前={}, 过滤后={}, 最终Top={}", 
                beforeFilter, afterFilter, Math.min(maxResults, afterFilter));

        // 取 topN
        return results.subList(0, Math.min(maxResults, results.size()));
    }

    /**
     * 【根据来源删除记忆】
     */
    public void deleteBySource(String source) throws SQLException {
        String delete = "DELETE FROM chunks WHERE source = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(delete)) {
            pstmt.setString(1, source);
            int count = pstmt.executeUpdate();
            log.info("[VectorMemory] 删除来源 '{}' 的 {} 条记忆", source, count);
        }
    }


    /**
     * 【关闭数据库连接】
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("[VectorMemory] 数据库连接已关闭");
            }
        } catch (SQLException e) {
            log.error("[VectorMemory] 关闭数据库失败: {}", e.getMessage());
        }
    }

    // ========== 私有工具方法 ==========

    /**
     * 【将 float 数组转换为字节数组】
     */
    private byte[] floatArrayToBytes(float[] floats) {
        byte[] bytes = new byte[floats.length * 4];
        for (int i = 0; i < floats.length; i++) {
            int bits = Float.floatToIntBits(floats[i]);
            bytes[i * 4] = (byte) (bits & 0xFF);
            bytes[i * 4 + 1] = (byte) ((bits >> 8) & 0xFF);
            bytes[i * 4 + 2] = (byte) ((bits >> 16) & 0xFF);
            bytes[i * 4 + 3] = (byte) ((bits >> 24) & 0xFF);
        }
        return bytes;
    }

    /**
     * 【将字节数组转换为 float 数组】
     */
    private float[] bytesToFloatArray(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            int bits = (bytes[i * 4] & 0xFF) |
                      ((bytes[i * 4 + 1] & 0xFF) << 8) |
                      ((bytes[i * 4 + 2] & 0xFF) << 16) |
                      ((bytes[i * 4 + 3] & 0xFF) << 24);
            floats[i] = Float.intBitsToFloat(bits);
        }
        return floats;
    }

    /**
     * 【计算余弦相似度】
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0f;
        }

        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }

        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 【ResultSet 转换为 MemoryChunk】
     */
    private MemoryChunk resultSetToChunk(ResultSet rs) throws SQLException {
        MemoryChunk chunk = new MemoryChunk();
        chunk.setId(rs.getString("id"));
        chunk.setContent(rs.getString("content"));
        chunk.setContentHash(rs.getString("content_hash"));
        chunk.setSource(rs.getString("source"));
        chunk.setSourcePath(rs.getString("source_path"));
        chunk.setTimestamp(rs.getLong("timestamp"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        return chunk;
    }
}
