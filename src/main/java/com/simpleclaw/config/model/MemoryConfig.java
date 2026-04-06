package com.simpleclaw.config.model;

/**
 * 【记忆检索配置】
 * 
 * 用于控制混合检索（RAG + 关键词）的权重和候选集大小。
 */
public class MemoryConfig {
    private float vectorWeight = 0.7f;
    private float textWeight = 0.3f;
    private int candidateMultiplier = 4;
    private int maxInjectedChars = 4000;

    public float getVectorWeight() { return vectorWeight; }
    public void setVectorWeight(float vectorWeight) { this.vectorWeight = vectorWeight; }
    
    public float getTextWeight() { return textWeight; }
    public void setTextWeight(float textWeight) { this.textWeight = textWeight; }
    
    public int getCandidateMultiplier() { return candidateMultiplier; }
    public void setCandidateMultiplier(int candidateMultiplier) { this.candidateMultiplier = candidateMultiplier; }
    
    public int getMaxInjectedChars() { return maxInjectedChars; }
    public void setMaxInjectedChars(int maxInjectedChars) { this.maxInjectedChars = maxInjectedChars; }
}
