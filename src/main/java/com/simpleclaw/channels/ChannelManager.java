package com.simpleclaw.channels;

import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import com.simpleclaw.config.Config;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 【渠道管理器】
 * 
 * 功能说明：
 * 此类负责管理所有消息渠道的初始化和运行：
 * 1. 根据配置初始化各个渠道（钉钉、QQ、微信）
 * 2. 启动 outbound 消息分发循环
 * 3. 从 MessageBus 获取消息并分发给对应的渠道发送
 * 4. 统一管理所有渠道的生命周期（启动、停止）
 * 
 * 支持的消息渠道：
 * - 钉钉（dingtalk）：企业钉钉机器人
 * - QQ（qq）：QQ机器人
 * - 微信（wechat）：企业微信/微信公众号（新增）
 * 
 * 工作流程：
 * 1. 构造时调用initChannels()初始化所有启用的渠道
 * 2. startAll()启动所有渠道和消息分发循环
 * 3. dispatchOutbound()循环从bus获取消息并分发
 * 4. stop()停止所有渠道和线程池
 */
@Slf4j
public class ChannelManager {

    /**
     * 应用配置对象
     */
    private final Config config;

    /**
     * 消息总线，用于接收和发送消息
     */
    private final MessageBus bus;

    /**
     * 渠道映射表：渠道名称 -> 渠道实例
     */
    private final Map<String, BaseChannel> channels = new HashMap<>();

    /**
     * 线程池，用于异步运行各个渠道
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 运行状态标志
     */
    private volatile boolean running = true;

    /**
     * 构造函数
     * 
     * @param config 应用配置
     * @param bus 消息总线
     */
    public ChannelManager(Config config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
        initChannels();
    }

    /**
     * 【初始化所有渠道】
     * 
     * 根据配置创建各个渠道的实例：
     * 1. 检查钉钉配置，如果启用则创建DingTalkChannel
     * 2. 检查QQ配置，如果启用则创建QQChannel
     * 3. 检查微信配置，如果启用则创建WeChatChannel（新增）
     * 
     * 所有创建的渠道实例都会注册到channels映射表中
     */
    public void initChannels() {

        // 【初始化QQ渠道】
        if (config.getChannels().getQq().isEnabled()) {
            QQChannel qq = new QQChannel(
                    config.getChannels().getQq(),
                    config.getGateway(),
                    bus);
            channels.put("qq", qq);
            log.info("QQ渠道已初始化");
        }


        // 【初始化微信渠道 - 个人微信模式（ilinkai API）】
        if (config.getChannels().getWeixin().isEnabled()) {
            WeixinChannel weixin = new WeixinChannel(
                    config.getChannels().getWeixin(),
                    config.getGateway(),
                    bus);
            channels.put("weixin", weixin);
            log.info("微信渠道（个人微信）已初始化");
        }

        // 【初始化 CLI 渠道】
        CliChannel cli = new CliChannel(bus);
        channels.put("cli", cli);
        log.info("CLI 渠道已初始化");

        // 输出统计信息
        log.info("共初始化 {} 个渠道", channels.size());
    }

    /** 启动 dispatchOutbound 循环与各 channel.start() */
    public void startAll() {
        executor.submit(this::dispatchOutbound);
        for (BaseChannel ch : channels.values()) {
            executor.submit(ch::start);
        }
    }

    /** 从 bus 取 outbound 分发给已注册 channel */
    public void dispatchOutbound() {
        while (running && bus.isRunning()) {
            try {
                OutboundMessage msg = bus.consumeOutbound(2, TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }
                log.debug("取出消息: channel={}, chatId={}, content=...", msg.getChannel(), msg.getChatId());
                BaseChannel ch = channels.get(msg.getChannel());
                if (ch != null) {
                    log.debug("分发消息到渠道: {}", msg.getChannel());
                    ch.send(msg);
                } else {
                    log.warn("未找到渠道: {}", msg.getChannel());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("dispatchOutbound 循环已结束");
    }

    public void stop() {
        running = false;
        for (BaseChannel ch : channels.values()) {
            ch.stop();
        }
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, BaseChannel> getChannels() {
        return new HashMap<>(channels);
    }
}
