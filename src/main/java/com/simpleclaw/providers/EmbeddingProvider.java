package com.simpleclaw.providers;

/**
 * 【Embedding 提供商接口】
 *
 * 负责将文本转换为向量嵌入（embedding），用于语义相似度计算。
 *
 * 实现方式：
 * 1. 本地 ONNX 模型（离线，无 API 费用）
 * 2. OpenAI API（远程，高质量）
 * 3. 其他兼容 OpenAI 接口的服务
 */
public interface EmbeddingProvider {

    /**
     * 【获取向量维度】
     *
     * 返回该提供商生成的向量维度（如 384、768、1536 等）
     *
     * @return 向量维度
     */
    int getDimension();

    /**
     * 【文本向量化】
     *
     * 将文本转换为向量表示
     *
     * @param text 输入文本
     * @return 向量数组（float 数组）
     */
    float[] embed(String text);

    /**
     * 【批量向量化】
     *
     * 批量处理多个文本，提高效率
     *
     * @param texts 文本数组
     * @return 向量数组的数组
     */
    float[][] embedBatch(String[] texts);
}
