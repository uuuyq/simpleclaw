package com.simpleclaw.gateway;

import com.simpleclaw.agent.AgentLoop;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import com.simpleclaw.channels.ChannelManager;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【网关服务器】
 *
 * 基于 WebSocket 的多客户端网关，支持：
 * 1. 多客户端同时连接（CLI、WebUI、手机App）
 * 2. 远程访问（通过 SSH 隧道或 VPN）
 * 3. 实时消息推送
 * 4. 与现有 AgentLoop、ChannelManager 集成
 *
 * 协议：
 * - 请求:  {"type":"req","id":"uuid","method":"agent","payload":{"content":"你好"}}
 * - 响应:  {"type":"res","id":"uuid","payload":{"content":"回复"}}
 * - 事件:  {"type":"event","method":"agent","payload":{"delta":"流式内容"}}
 */
@Slf4j
public class GatewayServer extends WebSocketServer {

    // 【客户端计数器】用于生成客户端ID
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    // 【客户端映射】conn -> clientId
    private final Map<WebSocket, String> clientIds = new ConcurrentHashMap<>();

    // 【反向映射】clientId -> conn
    private final Map<String, WebSocket> connections = new ConcurrentHashMap<>();

    // 【核心组件引用】
    private final MessageBus bus;
    private final AgentLoop agent;
    private final ChannelManager channelManager;

    // 【运行状态】
    private volatile boolean running = true;

    /**
     * 【构造函数】
     *
     * @param port            监听端口（默认 18789）
     * @param bus             消息总线
     * @param agent           Agent 循环
     * @param channelManager  渠道管理器
     */
    public GatewayServer(int port, MessageBus bus, AgentLoop agent, ChannelManager channelManager) {
        super(new InetSocketAddress(port));
        this.bus = bus;
        this.agent = agent;
        this.channelManager = channelManager;
    }

    /**
     * 【启动网关】
     *
     * 启动 WebSocket 服务器，开始接受客户端连接。
     */
    @Override
    public void start() {
        log.info("启动网关服务器，端口: {}", getPort());
        super.start();

        // 启动事件转发线程
        startEventForwarder();
    }

    /**
     * 【停止网关】
     */
    @Override
    public void stop() throws InterruptedException {
        log.info("停止网关服务器");
        running = false;
        super.stop();
    }

    /**
     * 【新连接建立】
     *
     * 当客户端连接时触发，分配客户端ID。
     */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = "client-" + clientCounter.incrementAndGet();
        clientIds.put(conn, clientId);
        connections.put(clientId, conn);

        String remoteAddr = conn.getRemoteSocketAddress().toString();
        log.info("客户端连接：{} 来自 {}", clientId, remoteAddr);

        // 发送欢迎消息
        Map<String, Object> connectedData = new ConcurrentHashMap<>();
        connectedData.put("clientId", clientId);
        connectedData.put("message", "欢迎使用 SimpleClaw Gateway");
        sendMessage(conn, GatewayMessage.event("connected", connectedData));
    }

    /**
     * 【连接关闭】
     *
     * 当客户端断开时触发，清理资源。
     */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientId = clientIds.remove(conn);
        if (clientId != null) {
            connections.remove(clientId);
            log.info("客户端断开: {} 原因: {}", clientId, reason);
        }
    }

    /**
     * 【收到消息】
     *
     * 处理客户端发送的消息，根据 method 分发到不同处理器。
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        String clientId = clientIds.get(conn);
        if (clientId == null) {
            return; // 未识别的客户端
        }

        // 解析消息
        GatewayMessage msg = GatewayMessage.fromJson(message);
        if (msg == null) {
            sendError(conn, null, "消息格式错误");
            return;
        }

        log.debug("收到消息 [{}]: {}", clientId, msg.getMethod());

        // 根据类型处理
        if (!"req".equals(msg.getType())) {
            sendError(conn, msg.getId(), "只支持 req 类型消息");
            return;
        }

        // 根据方法分发
        switch (msg.getMethod()) {
            case "agent":
                handleAgentRequest(conn, msg);
                break;
            case "send":
                handleSendRequest(conn, msg);
                break;
            case "health":
                handleHealthRequest(conn, msg);
                break;
            case "status":
                handleStatusRequest(conn, msg);
                break;
            default:
                sendError(conn, msg.getId(), "未知方法: " + msg.getMethod());
        }
    }

    /**
     * 【处理 Agent 请求】
     *
     * 运行 Agent 处理用户输入，支持流式响应。
     */
    private void handleAgentRequest(WebSocket conn, GatewayMessage msg) {
        String content = msg.getStringParam("content");
        if (content == null || content.isEmpty()) {
            sendError(conn, msg.getId(), "缺少 content 参数");
            return;
        }

        String clientId = clientIds.get(conn);

        try {
            // 调用 Agent（带流式回调）
            String response = agent.processDirect(
                    content,
                    "gateway",
                    clientId,
                    delta -> {
                        Map<String, Object> deltaData = new ConcurrentHashMap<>();
                        deltaData.put("delta", delta);
                        deltaData.put("requestId", msg.getId());
                        sendMessage(conn, GatewayMessage.event("agent_delta", deltaData));
                    }
            );

            // 发送最终响应
            Map<String, Object> payload = new ConcurrentHashMap<>();
            payload.put("content", response);
            payload.put("sessionKey", "gateway:" + clientId);
            sendMessage(conn, GatewayMessage.response(msg.getId(), payload));

        } catch (Exception e) {
            sendError(conn, msg.getId(), "Agent 执行错误：" + e.getMessage());
        }
    }

    /**
     * 【处理发送消息请求】
     *
     * 发送消息到指定渠道（微信、QQ 等）。
     */
    private void handleSendRequest(WebSocket conn, GatewayMessage msg) {
        String channel = msg.getStringParam("channel");
        String chatId = msg.getStringParam("chatId");
        String content = msg.getStringParam("content");

        if (channel == null || content == null) {
            sendError(conn, msg.getId(), "缺少 channel 或 content 参数");
            return;
        }

        // 构造出站消息
        OutboundMessage outMsg = new OutboundMessage(
                channel,
                chatId != null ? chatId : "gateway",
                content
        );

        // 发布到总线
        bus.publishOutbound(outMsg);

        // 发送成功响应
        Map<String, Object> sentData = new ConcurrentHashMap<>();
        sentData.put("status", "sent");
        sentData.put("channel", channel);
        sendMessage(conn, GatewayMessage.response(msg.getId(), sentData));
    }

    /**
     * 【处理健康检查请求】
     */
    private void handleHealthRequest(WebSocket conn, GatewayMessage msg) {
        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("status", "ok");
        payload.put("clients", connections.size());
        payload.put("timestamp", System.currentTimeMillis());
        sendMessage(conn, GatewayMessage.response(msg.getId(), payload));
    }

    /**
     * 【处理状态查询请求】
     */
    private void handleStatusRequest(WebSocket conn, GatewayMessage msg) {
        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("clients", connections.size());
        payload.put("clientIds", connections.keySet());
        payload.put("channels", channelManager.getChannels().keySet());
        payload.put("inboundQueue", bus.getInboundSize());
        payload.put("outboundQueue", bus.getOutboundSize());
        sendMessage(conn, GatewayMessage.response(msg.getId(), payload));
    }

    /**
     * 【发送消息到客户端】
     */
    private void sendMessage(WebSocket conn, GatewayMessage msg) {
        if (conn != null && conn.isOpen()) {
            conn.send(msg.toJson());
        }
    }

    /**
     * 【发送错误响应】
     */
    private void sendError(WebSocket conn, String requestId, String error) {
        log.error("错误：{}", error);
        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("error", error);
        payload.put("success", false);
        GatewayMessage msg = GatewayMessage.response(requestId != null ? requestId : "", payload);
        sendMessage(conn, msg);
    }

    /**
     * 【广播消息给所有客户端】
     *
     * @param msg 要广播的消息
     */
    public void broadcast(GatewayMessage msg) {
        String json = msg.toJson();
        for (WebSocket conn : connections.values()) {
            if (conn.isOpen()) {
                conn.send(json);
            }
        }
    }

    /**
     * 【启动事件转发器】
     *
     * 将 MessageBus 的出站消息转发给所有连接的客户端。
     */
    private void startEventForwarder() {
        Thread forwarder = new Thread(() -> {
            log.info("启动事件转发器");
            while (running) {
                try {
                    // 从总线获取出站消息（带超时）
                    OutboundMessage msg = bus.consumeOutbound(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (msg == null) {
                        continue;
                    }

                    // 构造事件消息
                    Map<String, Object> eventData = new ConcurrentHashMap<>();
                    eventData.put("channel", msg.getChannel());
                    eventData.put("chatId", msg.getChatId());
                    eventData.put("content", msg.getContent());
                    GatewayMessage event = GatewayMessage.event("chat", eventData);

                    // 广播给所有客户端
                    broadcast(event);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("事件转发器已停止");
        });
        forwarder.setName("GatewayEventForwarder");
        forwarder.setDaemon(true);
        forwarder.start();
    }

    /**
     * 【发生错误】
     */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        String clientId = conn != null ? clientIds.get(conn) : "unknown";
        log.error("客户端 {} 错误: {}", clientId, ex.getMessage());
    }

    /**
     * 【服务器启动完成】
     */
    @Override
    public void onStart() {
        log.info("网关服务器已启动，等待连接...");
    }
}
