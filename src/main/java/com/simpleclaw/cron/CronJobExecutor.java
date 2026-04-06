package com.simpleclaw.cron;

import com.simpleclaw.agent.AgentLoop;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import com.simpleclaw.config.model.PromptsConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * 【定时任务执行器】
 *
 * 负责执行到期的定时任务，将任务分发给 Agent 处理。
 *
 * 【设计说明】
 * - 将定时任务执行逻辑从 GatewayCommand 抽离出来
 * - 保持 CronService 纯粹，不直接依赖 Agent
 * - 通过此执行器桥接 CronService 和 AgentLoop
 */
@Slf4j
public class CronJobExecutor {

    // 【CLI 渠道标识】
    private static final String CLI_CHANNEL = "cli";

    // 【CLI 交互模式的 chatId】
    private static final String CLI_CHAT_ID = "interactive";

    // 【定时任务默认渠道】
    private static final String CRON_CHANNEL = "cron";

    // 【定时任务执行回复模板】
    private static final String CRON_JOB_RESPONSE_TEMPLATE =
            "⏰ 定时任务提醒\n\n%s";

    // 【CLI 定时任务结果格式】
    private static final String CLI_CRON_RESULT_FORMAT =
            "\n[定时任务提醒] %s\n回复: %s\n> ";

    // 【Agent 实例】用于处理任务
    private final AgentLoop agent;

    // 【消息总线】用于发送结果到渠道
    private final MessageBus bus;

    /**
     * 【构造函数】
     *
     * @param agent Agent 实例
     * @param bus   消息总线
     */
    public CronJobExecutor(AgentLoop agent, MessageBus bus) {
        this.agent = agent;
        this.bus = bus;
    }

    /**
     * 【执行任务】
     *
     * 这是 CronService 回调的入口方法。
     *
     * @param job 定时任务
     */
    public void execute(SimpleJob job) {
        String msg = job.getMessage();
        log.info("[Cron] 执行任务: {} - {}", job.getId(), msg);

        // 【获取任务关联的 channel 和 chatId】
        String jobChannel = job.getChannel() != null ? job.getChannel() : CLI_CHANNEL;
        String jobChatId = job.getChatId() != null ? job.getChatId() : CLI_CHAT_ID;

        // 【构建任务执行 Prompt】
        String taskPrompt = buildTaskPrompt(job);

        // 【调用 Agent 处理任务】
        String response = agent.processDirect(taskPrompt, jobChannel, jobChatId, null);

        log.info("[Cron] 任务 {} 执行完成", job.getId());

        // 【发送结果到指定渠道】
        sendResultToChannel(job, response);
    }

    /**
     * 【构建任务执行 Prompt】
     *
     * 使用 PromptsConfig 中的模板构建任务执行上下文。
     *
     * @param job 定时任务
     * @return 任务执行 Prompt
     */
    private String buildTaskPrompt(SimpleJob job) {
        return String.format(PromptsConfig.CRON_JOB_EXECUTION_PROMPT,
                job.getId(),
                job.getMessage());
    }

    /**
     * 【发送任务结果到指定渠道】
     *
     * @param job      定时任务
     * @param response Agent 执行结果
     */
    private void sendResultToChannel(SimpleJob job, String response) {
        if (response == null || response.isEmpty()) {
            return;
        }

        String channel = job.getChannel();
        String chatId = job.getChatId();

        if (channel == null || channel.isEmpty()) {
            return;
        }

        // 【CLI 渠道】直接打印到控制台
        if (CLI_CHANNEL.equals(channel)) {
            System.out.printf(CLI_CRON_RESULT_FORMAT,
                    job.getMessage(), response);
            return;
        }

        // 【其他渠道】通过 MessageBus 发送
        bus.publishOutbound(new OutboundMessage(
                channel,
                chatId != null ? chatId : CRON_CHANNEL + ":" + job.getId(),
                response
        ));
    }
}
