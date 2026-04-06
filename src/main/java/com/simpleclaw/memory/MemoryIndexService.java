package com.simpleclaw.memory;

import com.simpleclaw.memory.model.MemoryChunk;
import com.simpleclaw.providers.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 【记忆索引服务】
 *
 * 负责扫描和索引记忆文件（MEMORY.md 和 memory/*.md）：
 * 1. 文件级哈希检查（第一层）- 跳过未变更的文件
 * 2. 分块级哈希检查（第二层）- 复用已缓存的向量
 * 3. 调用 Embedding API 生成新向量
 *
 * 【设计原则】：
 * - 独立于 MemoryManager，可单独使用（如定时扫描）
 * - 幂等性：重复索引相同内容不会产生重复数据
 */
@Slf4j
public class MemoryIndexService {

    private final Path workspace;
    private final Path memoryDir;
    private final DocumentChunker chunker;
    private final VectorMemoryStore vectorStore;
    private final EmbeddingProvider embeddingProvider;

    /**
     * 【构造函数】
     *
     * @param workspace 工作空间路径
     * @param memoryDir memory 目录路径
     * @param chunker 文档分块器
     * @param vectorStore 向量存储
     * @param embeddingProvider Embedding 提供商
     */
    public MemoryIndexService(Path workspace, Path memoryDir,
                              DocumentChunker chunker, VectorMemoryStore vectorStore,
                              EmbeddingProvider embeddingProvider) {
        this.workspace = workspace;
        this.memoryDir = memoryDir;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 【索引记忆目录】
     *
     * 扫描 memory/ 目录下的所有 Markdown 文件，
     * 同时索引用户手动维护的 MEMORY.md，
     * 分块并建立向量索引。
     */
    public void indexMemoryDirectory() {
        try {
            log.info("[MemoryIndexService] 开始索引记忆目录: {}", memoryDir);

            // 【索引用户手动维护的 MEMORY.md】
            Path memoryMd = memoryDir.resolve("MEMORY.md");
            if (Files.isRegularFile(memoryMd)) {
//                log.info("[MemoryIndexService] 索引 MEMORY.md");
                indexFile(memoryMd);
            }

            // 【查找 memory/ 目录下所有 Markdown 文件】
            if (Files.isDirectory(memoryDir)) {
                try (Stream<Path> paths = Files.walk(memoryDir)) {
                    paths.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".md"))
                            .forEach(this::indexFile);
                }
            }

            log.info("[MemoryIndexService] 记忆目录索引完成");

        } catch (IOException e) {
            log.error("[MemoryIndexService] 索引记忆目录失败: {}", e.getMessage());
        }
    }

    /**
     * 【索引单个文件】
     *
     * 【两层哈希机制】：
     * 1. 第一层（文件级）：检查文件内容哈希，未变更则跳过整个文件
     * 2. 第二层（分块级）：检查分块内容哈希，命中缓存则复用向量，不调用 API
     *
     * @param file 要索引的文件
     */
    public void indexFile(Path file) {
        try {
            String relativePath = workspace.relativize(file).toString();
            log.debug("[MemoryIndexService] 索引文件: {}", relativePath);

            // 【第一层哈希：检查文件是否变更】
            String fileContent = Files.readString(file, StandardCharsets.UTF_8);
            String fileHash = computeFileHash(fileContent);

            String existingHash = vectorStore.getFileContentHash(relativePath);
            if (fileHash.equals(existingHash)) {
                log.debug("[MemoryIndexService] 文件未变更，跳过索引: {}", relativePath);
                return;
            }

            // 【分块】
            List<MemoryChunk> chunks = chunker.chunkFile(file);

            if (chunks.isEmpty()) {
                log.debug("[MemoryIndexService] 文件 '{}' 分块结果为 0", relativePath);
                return;
            }

            // 【第二层哈希：批量获取缓存的嵌入向量】
            List<String> contentHashes = chunks.stream()
                    .map(MemoryChunk::getContentHash)
                    .collect(Collectors.toList());
            Map<String, float[]> cachedEmbeddings = vectorStore.getCachedEmbeddingsBatch(contentHashes);

            log.debug("[MemoryIndexService] 分块缓存命中: {}/{} 块",
                    cachedEmbeddings.size(), chunks.size());

            // 【计算向量并存储】
            int cacheHits = 0;
            int apiCalls = 0;

            for (MemoryChunk chunk : chunks) {
                float[] embedding = cachedEmbeddings.get(chunk.getContentHash());

                if (embedding != null) {
                    // 【缓存命中】复用已缓存的向量
                    chunk.setEmbedding(embedding);
                    cacheHits++;
                } else {
                    // 【缓存未命中】调用嵌入 API
                    embedding = embeddingProvider.embed(chunk.getContent());
                    chunk.setEmbedding(embedding);

                    // 缓存新计算的向量
                    vectorStore.cacheEmbedding(chunk.getContentHash(), embedding);
                    apiCalls++;
                }

                vectorStore.addChunk(chunk);
            }

            // 【更新文件哈希】
            vectorStore.updateFileContentHash(relativePath, fileHash, Files.size(file));

            log.info("[MemoryIndexService] 文件 '{}' 索引完成: {} 块 (缓存命中: {}, API调用: {})",
                    relativePath, chunks.size(), cacheHits, apiCalls);

        } catch (Exception e) {
            log.error("[MemoryIndexService] 索引文件失败 {}: {}", file, e.getMessage());
        }
    }

    /**
     * 【计算文件哈希】
     *
     * 使用 SHA-256 生成文件内容哈希
     *
     * @param content 文件内容
     * @return 哈希字符串
     */
    private String computeFileHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }
}
