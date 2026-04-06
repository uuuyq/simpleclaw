package com.simpleclaw.memory;

import com.simpleclaw.memory.model.MemoryChunk;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 【文档分块器】
 *
 * 将长文档切分为适合向量化的文本块。
 *
 * 参考 OpenClaw 策略：
 * - 分块大小：约 400 tokens（实际用字符数估算）
 * - 重叠：80 tokens（确保上下文连续性）
 * - 优先按段落边界切分，保持语义完整性
 */
@Slf4j
public class DocumentChunker {

    // 【目标分块大小】约 400 tokens ≈ 400 字符（中文约 200 字）
    private static final int TARGET_CHUNK_SIZE = 400;

    // 【重叠大小】约 80 tokens ≈ 80 字符
    private static final int OVERLAP_SIZE = 80;

    // 【最小分块大小】避免过小块
    private static final int MIN_CHUNK_SIZE = 200;

    /**
     * 【分块文档】
     *
     * @param content   文档内容
     * @param source    来源名称
     * @param sourcePath 来源路径
     * @return 分块后的记忆块列表
     */
    public List<MemoryChunk> chunk(String content, String source, String sourcePath) {
        List<MemoryChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        // 【按段落分割】
        String[] paragraphs = content.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        long timestamp = System.currentTimeMillis();

        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }

            // 【如果当前段落本身超过目标大小，需要进一步分割】
            if (paragraph.length() > TARGET_CHUNK_SIZE) {
                // 先保存当前累积的内容
                if (currentChunk.length() >= MIN_CHUNK_SIZE) {
                    chunks.add(createChunk(
                            currentChunk.toString(),
                            source, sourcePath, timestamp, chunkIndex++
                    ));
                    currentChunk = new StringBuilder();
                }

                // 分割长段落
                chunks.addAll(splitLongParagraph(paragraph, source, sourcePath, timestamp, chunkIndex));
                chunkIndex += chunks.size();

                continue;
            }

            // 【尝试添加到当前块】
            if (currentChunk.length() + paragraph.length() + 2 <= TARGET_CHUNK_SIZE) {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            } else {
                // 【当前块已满，保存并新建】
                if (currentChunk.length() >= MIN_CHUNK_SIZE) {
                    chunks.add(createChunk(
                            currentChunk.toString(),
                            source, sourcePath, timestamp, chunkIndex++
                    ));
                }

                // 【重叠策略：保留上一块的部分内容】
                String overlap = getOverlap(currentChunk.toString());
                currentChunk = new StringBuilder(overlap);
                if (!overlap.isEmpty()) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 【保存最后一块】
        // 即使内容较短也保存，确保短文档也能被索引
        if (currentChunk.length() > 0) {
            chunks.add(createChunk(
                    currentChunk.toString(),
                    source, sourcePath, timestamp, chunkIndex
            ));
        }

        log.info("[Chunker] 文档 '{}' 分块完成: {} 块", source, chunks.size());
        return chunks;
    }

    /**
     * 【从文件分块】
     */
    public List<MemoryChunk> chunkFile(Path filePath) throws IOException {
        // 使用 UTF-8 编码读取文件
        String content = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8);
        String source = filePath.getFileName().toString();
        String sourcePath = filePath.toString();

        log.debug("[Chunker] 读取文件 '{}': {} 字符", source, content.length());

        List<MemoryChunk> chunks = chunk(content, source, sourcePath);

        if (chunks.isEmpty()) {
            log.debug("[Chunker] 文件 '{}' 分块结果为 0，内容长度: {}，可能内容太短或格式不符合要求" +
                    "（最小块大小: {} 字符）", source, content.length(), MIN_CHUNK_SIZE);
        }

        return chunks;
    }

    /**
     * 【分割长段落】
     *
     * 当单个段落超过目标大小时，按句子边界分割
     */
    private List<MemoryChunk> splitLongParagraph(String paragraph, String source,
                                                  String sourcePath, long timestamp, int startIndex) {
        List<MemoryChunk> chunks = new ArrayList<>();

        // 【按句子分割】简单按标点符号分割
        String[] sentences = paragraph.split("(?<=[。！？.!?])");

        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = startIndex;

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }

            if (currentChunk.length() + sentence.length() <= TARGET_CHUNK_SIZE) {
                currentChunk.append(sentence);
            } else {
                // 保存当前块
                if (currentChunk.length() >= MIN_CHUNK_SIZE) {
                    chunks.add(createChunk(
                            currentChunk.toString(),
                            source, sourcePath, timestamp, chunkIndex++
                    ));
                }

                // 新建块，添加重叠
                String overlap = getOverlap(currentChunk.toString());
                currentChunk = new StringBuilder(overlap);
                currentChunk.append(sentence);
            }
        }

        // 保存最后一块
        if (currentChunk.length() >= MIN_CHUNK_SIZE) {
            chunks.add(createChunk(
                    currentChunk.toString(),
                    source, sourcePath, timestamp, chunkIndex
            ));
        }

        return chunks;
    }

    /**
     * 【获取重叠文本】
     *
     * 从文本末尾提取指定长度的内容，用于下一块的开头
     */
    private String getOverlap(String text) {
        if (text.length() <= OVERLAP_SIZE) {
            return text;
        }

        // 【智能截断：尽量在句子边界】
        String overlap = text.substring(text.length() - OVERLAP_SIZE);

        // 找到第一个句子开始位置
        int sentenceStart = overlap.indexOf('。');
        if (sentenceStart == -1) {
            sentenceStart = overlap.indexOf('！');
        }
        if (sentenceStart == -1) {
            sentenceStart = overlap.indexOf('？');
        }
        if (sentenceStart == -1) {
            sentenceStart = overlap.indexOf('.');
        }

        if (sentenceStart > 0 && sentenceStart < overlap.length() - 10) {
            overlap = overlap.substring(sentenceStart + 1);
        }

        return overlap.trim();
    }

    /**
     * 【创建记忆块】
     *
     * ID 生成策略：使用 source + chunkIndex + content hash
     * 确保相同内容的 chunk 在多次索引时生成相同的 ID，避免重复
     */
    private MemoryChunk createChunk(String content, String source,
                                    String sourcePath, long timestamp, int chunkIndex) {
        // 计算内容哈希（用于第二层哈希缓存）
        String contentHash = computeContentHash(content);

        // 使用内容哈希生成稳定的 ID
        String id = String.format("chunk_%s_%d_%s", source, chunkIndex, contentHash.substring(0, 8));

        MemoryChunk chunk = new MemoryChunk(id, content, null, source, sourcePath, timestamp, chunkIndex);
        chunk.setContentHash(contentHash);
        return chunk;
    }

    /**
     * 【计算内容哈希】
     *
     * 使用 SHA-256 生成内容哈希，用于嵌入缓存
     */
    private String computeContentHash(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            // 如果 SHA-256 不可用，回退到简单哈希
            return String.valueOf(content.hashCode());
        }
    }

}
