package com.simpleclaw.interfaces.cli.commands;

import com.simpleclaw.agent.AgentLoop;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.Config;
import com.simpleclaw.config.ConfigLoader;
import com.simpleclaw.cron.SimpleJob;
import com.simpleclaw.cron.WheelCronService;
import com.simpleclaw.providers.ProviderFactory;
import com.simpleclaw.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 【Agent 子命令】
 *
 * 以 CLI 方式运行 Agent，支持单条消息模式（-m）或交互模式。
 * 功能：
 * 1. 加载配置并初始化 Agent
 * 2. 启动定时任务服务
 * 3. 处理用户输入并打印 Agent 回复
 */
@Deprecated
@Slf4j
@CommandLine.Command(name = "agent", description = "以 CLI 方式运行 Agent（单条或交互）")
public class AgentCommand implements Runnable {

    @CommandLine.Option(names = {"-m", "--message"}, description = "单条消息，不指定则进入交互模式")
    private String message;

    @Override
    public void run() {
        // 【步骤1】加载配置
        Config config = ConfigLoader.loadConfig();
        AgentConfig agentConfig = AgentConfig.fromConfig(config);

        // 【步骤2】初始化核心组件
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(ConfigLoader.getSessionsDir());
        WheelCronService cronService = new WheelCronService(ConfigLoader.getDataDir());

        // 【步骤3】创建 Agent
        AgentLoop agent = new AgentLoop(
                bus,
                ProviderFactory.fromConfig(config),
                agentConfig,
                sessionManager,
                cronService);

        // 【步骤4】设置定时任务回调
        cronService.setOnJob(job -> handleCronJob(agent, bus, job));

        // 【步骤5】启动服务
        cronService.start();
        agent.connectMcp();

        // 【步骤6】根据模式处理消息
        if (message != null && !message.isEmpty()) {
            processSingleMessage(agent, cronService);
        } else {
            processInteractiveMode(agent, cronService);
        }

        // 【步骤7】清理资源
        agent.closeMcp();
        cronService.stop();
    }

    /**
     * 【处理定时任务】
     * 使用任务的 channel 和 chatId 保持会话关联，告诉模型执行任务
     */
    private void handleCronJob(AgentLoop agent, MessageBus bus, SimpleJob job) {
        String msg = job.getMessage();
        String channel = job.getChannel();
        String chatId = job.getChatId();

        log.info("执行任务: {} - {}", job.getId(), msg);

        // 构建任务提示，告诉模型这是一个需要执行的定时任务
        String taskPrompt = "[定时任务] 请执行以下任务：\n" + msg;

        // 使用任务的 channel 和 chatId 保持会话关联，如果没有则使用默认值
        String actualChannel = channel != null ? channel : "cli";
        String actualChatId = chatId != null ? chatId : "cron";

        // 调用 Agent 处理任务
        String response = agent.processDirect(taskPrompt, actualChannel, actualChatId, null);

        log.info("任务 {} 执行完成", job.getId());

        // 发送结果到对应渠道
        if (response != null && !response.isEmpty() && channel != null && chatId != null) {
            bus.publishOutbound(new OutboundMessage(channel, chatId, response));
        }
    }
    
    /**
     * 【单条消息模式】处理一次消息并等待定时任务
     */
    @Deprecated
    private void processSingleMessage(AgentLoop agent, WheelCronService cronService) {
        boolean[] streamed = { false };
        String response = agent.processDirect(message, "cli", "direct", delta -> {
            streamed[0] = true;
            System.out.print(delta);
            System.out.flush();
        });
        if (!streamed[0] && response != null && !response.isEmpty()) {
            System.out.print(response);
        }
        log.info("");

        // 如果有定时任务，等待它们执行
        waitForCronJobs(cronService);
    }

    /**
     * 【交互模式】持续读取用户输入
     */
    private void processInteractiveMode(AgentLoop agent, WheelCronService cronService) {
        log.info("Enter message (empty line to exit):");
        log.info("[Cron] 定时服务已启动，创建的定时任务将会被执行");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    break;
                }
                boolean[] streamed = { false };
                String response = agent.processDirect(line, "cli", "interactive", delta -> {
                    streamed[0] = true;
                    System.out.print(delta);
                    System.out.flush();
                });
                if (!streamed[0] && response != null && !response.isEmpty()) {
                    System.out.print(response);
                }
                log.info("");
            }
        } catch (Exception e) {
            log.error("{}", e.getMessage());
        }
    }
    
    /**
     * 【等待定时任务】最多等待5分钟
     */
    private void waitForCronJobs(WheelCronService cronService) {
        if (!cronService.isRunning()) {
            return;
        }
        long jobCount = (Long) cronService.status().get("jobs");
        if (jobCount <= 0) {
            return;
        }
        
        log.info("检测到 {} 个定时任务，等待执行（按 Ctrl+C 取消）...", jobCount);
        try {
            for (int i = 0; i < 300 && cronService.isRunning(); i++) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
