package com.simpleclaw.interfaces.cli.commands;

import com.simpleclaw.agent.AgentLoop;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.channels.CliChannel;
import com.simpleclaw.channels.ChannelManager;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.Config;
import com.simpleclaw.config.ConfigLoader;
import com.simpleclaw.cron.CronJobExecutor;
import com.simpleclaw.cron.WheelCronService;
import com.simpleclaw.gateway.GatewayServer;
import com.simpleclaw.heartbeat.HeartbeatService;
import com.simpleclaw.providers.ProviderFactory;
import com.simpleclaw.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 【Gateway 子命令】
 *
 * 启动完整的 Gateway 服务，包含：
 * 1. Agent 消息处理循环
 * 2. 多渠道支持（微信、QQ、钉钉等）
 * 3. 定时任务服务
 * 4. 心跳服务
 * 5. 命令行交互
 */
@Slf4j
@CommandLine.Command(name = "gateway", description = "启动 Gateway（多渠道 + Agent）")
public class GatewayCommand implements Runnable {

    // 【常量定义】
    private static final String CLI_CHANNEL = "cli";
    private static final String CLI_CHAT_ID = "interactive";
    private static final int DEFAULT_GATEWAY_PORT = 18789;

    @Override
    public void run() {
        // 【步骤1】加载配置
        Config config = ConfigLoader.loadConfig();
        AgentConfig agentConfig = AgentConfig.fromConfig(config);

        // 【步骤2】初始化核心组件
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(ConfigLoader.getSessionsDir());
        WheelCronService cronService = new WheelCronService(ConfigLoader.getDataDir());
        ChannelManager channelManager = new ChannelManager(config, bus);

        // 【步骤3】创建 Agent
        AgentLoop agent = new AgentLoop(
                bus,
                ProviderFactory.fromConfig(config),
                agentConfig,
                sessionManager,
                cronService);

        // 【步骤4】设置定时任务执行器
        CronJobExecutor cronExecutor = new CronJobExecutor(agent, bus);
        cronService.setOnJob(cronExecutor::execute);

        // 【步骤5】创建并启动网关服务器
        int gatewayPort = config.getGateway() != null && config.getGateway().getPort() > 0
                ? config.getGateway().getPort()
                : DEFAULT_GATEWAY_PORT;
        GatewayServer gatewayServer = new GatewayServer(gatewayPort, bus, agent, channelManager);

        // 【步骤6】启动所有服务
        HeartbeatService heartbeat = new HeartbeatService(
            prompt -> agent.processDirect(prompt, "heartbeat", "service", null));

        ExecutorService executor = Executors.newCachedThreadPool();
        cronService.start();
        heartbeat.start();
        executor.submit(agent::run);
        channelManager.startAll();

        // 【步骤7】启动网关服务器
        try {
            gatewayServer.start();
        } catch (Exception e) {
            log.error("启动失败: {}", e.getMessage());
            return;
        }

        printStartupInfo(gatewayPort);

        // 【步骤8】等待关闭信号
        // 注意：CLI 渠道已由 ChannelManager.startAll() 启动，无需重复启动
        waitForShutdown();

        // 【步骤9】优雅关闭
        shutdown(agent, bus, channelManager, cronService, heartbeat, executor, gatewayServer);
    }

    /**
     * 【打印启动信息】
     */
    private void printStartupInfo(int port) {
        log.info("simpleclaw gateway started.");
        log.info("WebSocket: ws://localhost:{}", port);
        log.info("Commands: /chat <message> - Send message to agent");
        log.info("         /quit or Ctrl+C - Stop gateway");
        log.info("");
    }



    /**
     * 【等待关闭信号】
     */
    private void waitForShutdown() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 【优雅关闭所有服务】
     */
    private void shutdown(AgentLoop agent, MessageBus bus, ChannelManager channelManager,
                         WheelCronService cronService, HeartbeatService heartbeat,
                         ExecutorService executor, GatewayServer gatewayServer) {
        log.info("正在关闭服务...");

        if (gatewayServer != null) {
            try {
                gatewayServer.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        agent.stop();
        bus.stop();
        channelManager.stop();
        cronService.stop();
        heartbeat.stop();
        agent.closeMcp();
        executor.shutdown();

        log.info("所有服务已关闭");
    }
}
