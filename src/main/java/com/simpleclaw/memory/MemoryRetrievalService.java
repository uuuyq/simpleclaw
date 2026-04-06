package com.simpleclaw.memory;

import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.memory.model.MemoryChunk;
import com.simpleclaw.providers.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.List;

/**
 * 【记忆检索服务】
 *
 * 负责长期记忆的检索和注入：
 * 1. 混合检索（向量相似度 + 关键词匹配）
 * 2. 记忆注入到 System Prompt
 * 3. 预算控制（Token/字符数限制）
 *
 * 【与 SessionCompactionService 的区别】：
 * - SessionCompactionService: 处理会话压缩（Token 超限时的摘要生成）
 * - MemoryRetrievalService: 处理长期记忆的检索和注入
 */
@Slf4j
public class MemoryRetrievalService {

    // ========== 依赖组件 ==========

    /** 向量记忆存储 */
    private final VectorMemoryStore vectorStore;

    /** Embedding 提供商 */
    private final EmbeddingProvider embeddingProvider;

    /** Agent 配置 */
    private final AgentConfig agentConfig;

    // ========== 默认参数 ==========

    /** 默认最大记忆条数 */
    private static final int DEFAULT_MAX_MEMORIES = 6;

    /** 默认最低相关性分数 */
    private static final float DEFAULT_MIN_SCORE = 0.35f;

    /**
     * 【构造函数】
     *
     * @param vectorStore 向量记忆存储
     * @param embeddingProvider Embedding 提供商
     * @param agentConfig Agent 配置
     */
    public MemoryRetrievalService(VectorMemoryStore vectorStore,
                                   EmbeddingProvider embeddingProvider,
                                   AgentConfig agentConfig) {
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        this.agentConfig = agentConfig;
    }

    /**
     * 【注入记忆到 System Prompt】
     *
     * 根据用户查询检索相关记忆，并格式化为 system prompt 的一部分。
     *
     * @param systemPrompt 原始 system prompt
     * @param query        用户查询（用于检索相关记忆）
     * @return 注入记忆后的 system prompt
     */
    public String injectMemory(String systemPrompt, String query) {
        return injectMemory(systemPrompt, query, DEFAULT_MAX_MEMORIES, DEFAULT_MIN_SCORE);
    }

    /**
     * 【注入记忆（带参数）】
     *
     * @param systemPrompt 原始 system prompt
     * @param query        用户查询
     * @param maxResults   最大记忆条数
     * @param minScore     最低相关性分数
     * @return 注入记忆后的 system prompt
     */
    public String injectMemory(String systemPrompt, String query,
                               int maxResults, float minScore) {
        if (query == null || query.trim().isEmpty()) {
            return systemPrompt;
        }

        try {
            // 【检索相关记忆】
            List<MemoryChunk> memories = vectorStore.hybridSearch(query, maxResults, minScore);

            if (memories.isEmpty()) {
                log.debug("[MemoryRetrieval] 未找到相关记忆");
                return systemPrompt;
            }

            log.info("[MemoryRetrieval] 检索到 {} 条相关记忆", memories.size());

            // 【构建并截断记忆段落】
            String memorySection = buildMemorySection(memories, agentConfig.getMaxInjectedChars());

            // 【注入到 system prompt】
            return systemPrompt + "\n\n" + memorySection;

        } catch (SQLException e) {
            log.error("[MemoryRetrieval] 记忆检索失败: {}", e.getMessage());
            return systemPrompt;
        }
    }

    /**
     * 【构建记忆段落 - 带预算控制】
     *
     * 将记忆块格式化为 system prompt 的一部分，并确保总字符数不超过预算。
     *
     * @param memories 检索到的记忆列表
     * @param budget   最大允许的字符数
     * @return 格式化后的记忆段落
     */
    private String buildMemorySection(List<MemoryChunk> memories, int budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("<memory>\n");
        sb.append("以下是与当前对话相关的历史记忆：\n\n");

        int remainingBudget = budget - sb.length();
        for (int i = 0; i < memories.size(); i++) {
            MemoryChunk memory = memories.get(i);

            // 预计算当前条目的开销（索引 + 来源 + 换行等）
            String header = String.format("[%d] 来源: %s (相关性: %.2f)\n内容: ",
                    i + 1, memory.getSource(), memory.getScore());

            if (remainingBudget <= header.length() + 20) {
                break; // 剩余空间不足以放下一个有意义的条目
            }

            sb.append(header);

            // 【动态截断内容】
            String content = memory.getContent();
            int maxContentLen = remainingBudget - header.length() - 4; // 预留结尾的 \n\n
            if (content.length() > maxContentLen) {
                content = content.substring(0, Math.max(0, maxContentLen - 3)) + "...";
            }

            sb.append(content).append("\n\n");
            remainingBudget -= (header.length() + content.length() + 2);

            if (remainingBudget <= 0) break;
        }

        sb.append("</memory>");
        return sb.toString();
    }
}
