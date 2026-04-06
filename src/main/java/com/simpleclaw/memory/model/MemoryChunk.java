package com.simpleclaw.memory.model;

import lombok.Data;

/**
 * 【记忆块】
 *
 * 向量记忆的基本单元，存储文本片段及其向量表示。
 *
 * 设计说明：
 * - 每个 MemoryChunk 代表文档的一个分块
 * - 包含原始文本、向量嵌入、来源信息等
 * - 支持混合检索（向量相似度 + 关键词匹配）
 */
@Data
public class MemoryChunk {

    /**
     * 【唯一标识】
     * 格式: chunk_<timestamp>_<hash>
     */
    private String id;

    /**
     * 【文本内容】
     * 分块后的文本片段
     */
    private String content;

    /**
     * 【内容哈希】
     * 分块内容的哈希值，用于嵌入缓存（第二层哈希）
     */
    private String contentHash;

    /**
     * 【向量嵌入】
     * 文本的向量表示，用于语义相似度计算
     */
    private float[] embedding;

    /**
     * 【来源文档】
     * 该分块来自哪个文件/文档
     */
    private String source;

    /**
     * 【来源路径】
     * 文件的完整路径
     */
    private String sourcePath;

    /**
     * 【创建时间】
     * Unix 时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 【分块索引】
     * 在原文档中的分块序号（用于按顺序重组）
     */
    private int chunkIndex;

    /**
     * 【相关性分数】
     * 检索时计算的相关性得分（0-1之间）
     * 仅在搜索结果中使用，不持久化
     */
    private float score;

    // 显式添加 getter/setter 确保兼容性
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public float getScore() { return score; }
    public void setScore(float score) { this.score = score; }

    /**
     * 【默认构造函数】
     * 用于 JSON 反序列化
     */
    public MemoryChunk() {
    }

    /**
     * 【构造函数】
     *
     * @param id        唯一标识
     * @param content   文本内容
     * @param embedding 向量嵌入
     * @param source    来源文档
     * @param sourcePath 来源路径
     * @param timestamp 创建时间
     * @param chunkIndex 分块索引
     */
    public MemoryChunk(String id, String content, float[] embedding,
                       String source, String sourcePath, long timestamp, int chunkIndex) {
        this.id = id;
        this.content = content;
        this.embedding = embedding;
        this.source = source;
        this.sourcePath = sourcePath;
        this.timestamp = timestamp;
        this.chunkIndex = chunkIndex;
    }

    /**
     * 【获取向量维度】
     */
    public int getDimension() {
        return embedding != null ? embedding.length : 0;
    }


    @Override
    public String toString() {
        return String.format("MemoryChunk{id='%s', source='%s', content='%s...', score=%.3f}",
                id, source, content.substring(0, Math.min(50, content.length())), score);
    }
}
