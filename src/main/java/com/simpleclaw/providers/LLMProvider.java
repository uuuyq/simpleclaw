package com.simpleclaw.providers;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 提供商接口。发起对话补全，支持 tools；实现类由 ProviderFactory 根据 Config 创建。
 */
public interface LLMProvider {

    /**
     * 发起对话补全。messages 含 role、content，可选 tool_calls；tools 为 OpenAI 风格 function 定义列表。
     *
     * @param messages  消息列表
     * @param tools     工具定义（OpenAI function 格式）
     * @param model     模型名
     * @param maxTokens 最大 token
     * @param temperature 温度
     * @return 响应，含 content、toolCalls 等
     */
    LLMResponse chat(List<Map<String, Object>> messages,
                     List<Map<String, Object>> tools,
                     String model,
                     int maxTokens,
                     double temperature);

    /**
     * 同上，可选流式：当 streamConsumer 非 null 时，实现类可对 content 增量调用 streamConsumer，再返回完整 LLMResponse。
     * 默认实现忽略 streamConsumer 并调用无参重载。
     */
    default LLMResponse chat(List<Map<String, Object>> messages,
                             List<Map<String, Object>> tools,
                             String model,
                             int maxTokens,
                             double temperature,
                             Consumer<String> streamConsumer) {
        return chat(messages, tools, model, maxTokens, temperature);
    }
    
    /**
     * 带强制工具选择的对话补全。
     * 
     * 某些场景（如记忆整合）需要强制 LLM 调用特定工具。
     * 如果提供商不支持强制工具选择，实现类应抛出异常或返回错误响应。
     * 
     * @param messages 消息列表
     * @param tools 工具定义
     * @param model 模型名
     * @param maxTokens 最大 token
     * @param temperature 温度
     * @param toolChoice 强制工具选择，格式为 {"type": "function", "function": {"name": "tool_name"}}
     * @return 响应
     */
    default LLMResponse chatWithToolChoice(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           String model,
                                           int maxTokens,
                                           double temperature,
                                           Map<String, Object> toolChoice) {
        // 默认实现：忽略强制选择，直接调用普通 chat
        // 子类应覆盖此方法以支持强制工具选择
        return chat(messages, tools, model, maxTokens, temperature);
    }

    /** 返回该 provider 的默认模型名 */
    String getDefaultModel();

    /**
     * 【估算 Prompt Tokens】（可选实现）。
     * 
     * 如果提供商支持原生 Token 计数（如 OpenAI 的 tiktoken），则在此实现精确计算。
     * 否则返回 -1，系统将回退到启发式估算。
     * 
     * @param messages 消息列表
     * @param tools 工具定义列表
     * @return 估算的 Token 数，不支持则返回 -1
     */
    default int estimatePromptTokens(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools) {
        return -1; // 默认不支持，回退到启发式估算
    }
}
