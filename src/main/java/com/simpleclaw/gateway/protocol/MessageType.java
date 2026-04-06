package com.simpleclaw.gateway.protocol;

/**
 * 【消息类型枚举】
 * 
 * 定义 Gateway WebSocket 协议中的所有消息类型。
 * 基于 OpenClaw Gateway Architecture 设计。
 */
public enum MessageType {
    
    // ========== 基础协议消息 ==========
    
    /** 连接请求（客户端发送的第一个消息） */
    CONNECT,
    
    /** 连接响应（服务端回复） */
    CONNECT_OK,
    
    /** 连接错误 */
    CONNECT_ERROR,
    
    /** 心跳 ping */
    PING,
    
    /** 心跳 pong */
    PONG,
    
    // ========== 请求/响应消息 ==========
    
    /** 请求消息 */
    REQ,
    
    /** 响应消息 */
    RES,
    
    // ========== 服务器推送事件 ==========
    
    /** 事件消息 */
    EVENT,
    
    /** Agent 执行事件 */
    AGENT,
    
    /** 聊天消息事件 */
    CHAT,
    
    /** 在线状态事件 */
    PRESENCE,
    
    /** 健康状态事件 */
    HEALTH,
    
    /** 心跳事件 */
    HEARTBEAT,
    
    /** 定时任务事件 */
    CRON,
    
    /** 系统关闭事件 */
    SHUTDOWN,
    
    // ========== 订阅管理 ==========
    
    /** 订阅请求 */
    SUBSCRIBE,
    
    /** 取消订阅 */
    UNSUBSCRIBE;
    
    /**
     * 将字符串转换为消息类型（不区分大小写）
     */
    public static MessageType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return MessageType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
