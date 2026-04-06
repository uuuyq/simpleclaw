package com.simpleclaw.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 【网关消息类】
 *
 * 统一封装 WebSocket 消息格式，支持请求、响应、事件三种类型。
 *
 * 消息格式：
 * {
 *   "type": "req|res|event",
 *   "id": "uuid",
 *   "method": "agent|send|health",
 *   "payload": {}
 * }
 */
public class GatewayMessage {

    // 【JSON 处理器】线程安全，可复用
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 【消息类型】req=请求, res=响应, event=事件推送
    private String type;

    // 【消息ID】用于请求-响应匹配
    private String id;

    // 【方法名】agent/send/health 等
    private String method;

    // 【消息体】具体数据内容
    private Map<String, Object> payload;

    /**
     * 【默认构造】用于 JSON 反序列化
     */
    public GatewayMessage() {
        this.payload = new HashMap<>();
    }

    /**
     * 【构造请求消息】
     *
     * @param method  方法名
     * @param payload 请求参数
     */
    public GatewayMessage(String method, Map<String, Object> payload) {
        this.type = "req";
        this.id = UUID.randomUUID().toString();
        this.method = method;
        this.payload = payload != null ? payload : new HashMap<>();
    }

    /**
     * 【构造响应消息】
     *
     * @param id      对应请求的ID
     * @param payload 响应数据
     */
    public static GatewayMessage response(String id, Map<String, Object> payload) {
        GatewayMessage msg = new GatewayMessage();
        msg.type = "res";
        msg.id = id;
        msg.payload = payload != null ? payload : new HashMap<>();
        return msg;
    }

    /**
     * 【构造事件消息】
     *
     * @param event   事件类型
     * @param payload 事件数据
     */
    public static GatewayMessage event(String event, Map<String, Object> payload) {
        GatewayMessage msg = new GatewayMessage();
        msg.type = "event";
        msg.id = UUID.randomUUID().toString();
        msg.method = event;
        msg.payload = payload != null ? payload : new HashMap<>();
        return msg;
    }

    /**
     * 【序列化为 JSON】
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 【从 JSON 解析】
     */
    public static GatewayMessage fromJson(String json) {
        try {
            return MAPPER.readValue(json, GatewayMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Getter / Setter ==========

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    /**
     * 【便捷获取字符串参数】
     */
    public String getStringParam(String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }

    @Override
    public String toString() {
        return "GatewayMessage{" +
                "type='" + type + '\'' +
                ", id='" + id + '\'' +
                ", method='" + method + '\'' +
                ", payload=" + payload +
                '}';
    }
}
