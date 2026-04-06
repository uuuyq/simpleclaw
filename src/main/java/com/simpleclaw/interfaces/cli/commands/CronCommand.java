package com.simpleclaw.interfaces.cli.commands;

import com.simpleclaw.agent.AgentLoop;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.config.model.AgentConfig;
import com.simpleclaw.config.Config;
import com.simpleclaw.config.ConfigLoader;
import com.simpleclaw.cron.SimpleJob;
import com.simpleclaw.cron.WheelCronService;
import com.simpleclaw.providers.ProviderFactory;
import com.simpleclaw.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * cron 子命令：定时任务管理
 * 
 * 功能：
 * 1. 列出所有定时任务
 * 2. 添加新的定时任务
 * 3. 删除定时任务
 * 4. 启用/禁用定时任务
 * 5. 手动触发定时任务
 * 6. 查看服务状态
 * 
 * 使用示例：
 * - 列出任务：java -jar simpleclaw.jar cron list
 * - 添加一次性任务：java -jar simpleclaw.jar cron add --name="提醒" --at="2024-12-25T09:00:00" --message="圣诞节快乐"
 * - 添加周期任务：java -jar simpleclaw.jar cron add --name="日报" --every="3600000" --message="生成日报"
 * - 添加Cron任务：java -jar simpleclaw.jar cron add --name="早报" --cron="0 9 * * *" --message="发送早报"
 * - 删除任务：java -jar simpleclaw.jar cron remove --id="abc123"
 * - 启用/禁用：java -jar simpleclaw.jar cron enable --id="abc123" / java -jar simpleclaw.jar cron disable --id="abc123"
 * - 手动执行：java -jar simpleclaw.jar cron run --id="abc123"
 * - 查看状态：java -jar simpleclaw.jar cron status
 */

@Deprecated
@Slf4j
@CommandLine.Command(
    name = "cron",
    description = "定时任务管理 - 列出、添加、删除、管理定时任务",
    subcommands = {
        CronCommand.ListCommand.class,
        CronCommand.AddCommand.class,
        CronCommand.RemoveCommand.class,
        CronCommand.EnableCommand.class,
        CronCommand.DisableCommand.class,
        CronCommand.RunCommand.class,
        CronCommand.StatusCommand.class
    }
)
public class CronCommand implements Runnable {

    @Override
    public void run() {
        // 如果没有子命令，显示帮助信息
        log.info("定时任务管理命令");
        log.info("使用 'cron --help' 查看详细用法");
        log.info("");
        log.info("可用子命令：");
        log.info("  list     - 列出所有定时任务");
        log.info("  add      - 添加新的定时任务");
        log.info("  remove   - 删除定时任务");
        log.info("  enable   - 启用定时任务");
        log.info("  disable  - 禁用定时任务");
        log.info("  run      - 手动执行定时任务");
        log.info("  status   - 查看服务状态");
    }

    /**
     * 列出所有定时任务
     */
    @CommandLine.Command(name = "list", description = "列出所有定时任务")
    public static class ListCommand implements Runnable {

        @CommandLine.Option(names = {"-a", "--all"}, description = "包含已禁用的任务")
        private boolean includeDisabled;

        @Override
        public void run() {
            // 【步骤1】获取数据目录路径
            Path dataDir = ConfigLoader.getDataDir();
            
            // 【步骤2】创建Cron服务（不启动调度器，仅用于数据操作）
            WheelCronService cronService = new WheelCronService(dataDir);
            
            // 【步骤3】加载并列出所有任务
            List<SimpleJob> jobs = cronService.listJobs(includeDisabled);
            
            if (jobs.isEmpty()) {
                log.info("暂无定时任务");
                return;
            }
            
            // 【步骤 4】格式化输出任务列表
            log.info("定时任务列表（共 {} 个）：", jobs.size());
            log.info(createSeparatorLine(80));
            log.info(String.format("%-10s %-10s %-50s", "ID", "状态", "消息预览"));
            log.info(createSeparatorLine(80));
            
            for (SimpleJob job : jobs) {
                String status = job.isEnabled() ? "启用" : "禁用";
                String message = job.getMessage() != null ? job.getMessage() : "";
                if (message.length() > 45) {
                    message = message.substring(0, 45) + "...";
                }
                
                log.info(String.format("%-10s %-10s %-50s", job.getId(), status, message));
            }
            log.info(createSeparatorLine(80));
        }
        
        private String createSeparatorLine(int length) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append('-');
            }
            return sb.toString();
        }
        
    }

    /**
     * 添加新的定时任务
     */
    @CommandLine.Command(name = "add", description = "添加新的定时任务")
    public static class AddCommand implements Runnable {

        @CommandLine.Option(names = {"-n", "--name"}, required = true, description = "任务名称")
        private String name;

        @CommandLine.Option(names = {"-m", "--message"}, required = true, description = "要发送给Agent的消息")
        private String message;

        @CommandLine.Option(names = {"--at"}, description = "一次性执行时间 (ISO格式，如: 2024-12-25T09:00:00)")
        private String atTime;

        @CommandLine.Option(names = {"--every"}, description = "周期执行间隔（毫秒）")
        private Long everyMs;

        @CommandLine.Option(names = {"--cron"}, description = "Cron表达式 (如: '0 9 * * *' 每天9点)")
        private String cronExpr;

        @CommandLine.Option(names = {"--tz"}, description = "Cron时区 (如: Asia/Shanghai)")
        private String timezone;

        @CommandLine.Option(names = {"--deliver"}, description = "是否将结果发送到聊天频道")
        private boolean deliver;

        @CommandLine.Option(names = {"--channel"}, description = "发送结果的频道 (如: dingtalk, email)")
        private String channel;

        @CommandLine.Option(names = {"--to"}, description = "接收者ID")
        private String to;

        @CommandLine.Option(names = {"--delete-after-run"}, description = "执行后自动删除（仅适用于一次性任务）")
        private boolean deleteAfterRun;

        @Override
        public void run() {
            // 【步骤1】验证参数 - 必须指定一种调度方式
            int scheduleCount = 0;
            if (atTime != null) scheduleCount++;
            if (everyMs != null) scheduleCount++;
            if (cronExpr != null) scheduleCount++;
            
            if (scheduleCount == 0) {
                log.error("错误：必须指定一种调度方式 (--at, --every, 或 --cron)");
                System.exit(1);
            }
            
            if (scheduleCount > 1) {
                log.error("错误：只能指定一种调度方式");
                System.exit(1);
            }
            
            // 【步骤2】创建调度配置（简化为字符串）
            String schedule;
            try {
                if (atTime != null) {
                    // 解析一次性执行时间
                    Instant instant = Instant.parse(atTime);
                    schedule = String.valueOf(instant.toEpochMilli());
                } else if (everyMs != null) {
                    // 周期执行
                    schedule = String.valueOf(everyMs);
                } else {
                    // 不支持 cron 表达式，使用默认值
                    log.error("错误：简化版不支持 --cron，请使用 --at 或 --every");
                    System.exit(1);
                    return;
                }
            } catch (Exception e) {
                log.error("错误：无效的调度参数 - {}", e.getMessage());
                System.exit(1);
                return;
            }
            
            // 【步骤3】创建Cron服务并添加任务
            Path dataDir = ConfigLoader.getDataDir();
            WheelCronService cronService = new WheelCronService(dataDir);
            
            try {
                SimpleJob job = cronService.addJob(name, schedule, message, false, null, null, false);
                log.info("定时任务添加成功！");
                log.info("  ID: {}", job.getId());
                log.info("  消息: {}", job.getMessage());
            } catch (Exception e) {
                log.error("错误：添加任务失败 - {}", e.getMessage());
                System.exit(1);
            }
        }
    }

    /**
     * 删除定时任务
     */
    @CommandLine.Command(name = "remove", description = "删除定时任务")
    public static class RemoveCommand implements Runnable {

        @CommandLine.Option(names = {"-i", "--id"}, required = true, description = "任务ID")
        private String jobId;

        @Override
        public void run() {
            Path dataDir = ConfigLoader.getDataDir();
            WheelCronService cronService = new WheelCronService(dataDir);
            
            // 先获取任务信息用于确认
            Optional<SimpleJob> job = cronService.getJob(jobId);
            if (!job.isPresent()) {
                log.error("错误：未找到ID为 '{}' 的任务", jobId);
                System.exit(1);
                return;
            }
            
            // 删除任务
            boolean removed = cronService.removeJob(jobId);
            if (removed) {
                log.info("任务 '{}' ({}) 已删除", job.get().getMessage(), jobId);
            } else {
                log.error("错误：删除任务失败");
                System.exit(1);
            }
        }
    }

    /**
     * 启用定时任务
     */
    @CommandLine.Command(name = "enable", description = "启用定时任务")
    public static class EnableCommand implements Runnable {

        @CommandLine.Option(names = {"-i", "--id"}, required = true, description = "任务ID")
        private String jobId;

        @Override
        public void run() {
            Path dataDir = ConfigLoader.getDataDir();
            WheelCronService cronService = new WheelCronService(dataDir);
            
            Optional<SimpleJob> job = cronService.enableJob(jobId, true);
            if (job.isPresent()) {
                log.info("任务 '{}' ({}) 已启用", job.get().getMessage(), jobId);
            } else {
                log.error("错误：未找到ID为 '{}' 的任务", jobId);
                System.exit(1);
            }
        }
    }

    /**
     * 禁用定时任务
     */
    @CommandLine.Command(name = "disable", description = "禁用定时任务")
    public static class DisableCommand implements Runnable {

        @CommandLine.Option(names = {"-i", "--id"}, required = true, description = "任务ID")
        private String jobId;

        @Override
        public void run() {
            Path dataDir = ConfigLoader.getDataDir();
            WheelCronService cronService = new WheelCronService(dataDir);
            
            Optional<SimpleJob> job = cronService.enableJob(jobId, false);
            if (job.isPresent()) {
                log.info("任务 '{}' ({}) 已禁用", job.get().getMessage(), jobId);
            } else {
                log.error("错误：未找到ID为 '{}' 的任务", jobId);
                System.exit(1);
            }
        }
    }

    /**
     * 手动执行定时任务
     */
    @CommandLine.Command(name = "run", description = "手动执行定时任务（立即执行，不影响调度）")
    public static class RunCommand implements Runnable {

        @CommandLine.Option(names = {"-i", "--id"}, required = true, description = "任务ID")
        private String jobId;

        @CommandLine.Option(names = {"-f", "--force"}, description = "强制执行（即使任务被禁用）")
        private boolean force;

        @Override
        public void run() {
            Path dataDir = ConfigLoader.getDataDir();
            WheelCronService cronService = new WheelCronService(dataDir);
            
            // 先获取任务信息
            Optional<SimpleJob> job = cronService.getJob(jobId);
            if (!job.isPresent()) {
                log.error("错误：未找到ID为 '{}' 的任务", jobId);
                System.exit(1);
                return;
            }
            
            String message = job.get().getMessage();
            log.info("正在执行任务 '{}' ({})...", message, jobId);
            
            // 【创建Agent来处理消息】
            log.info("正在初始化Agent...");
            Config config = ConfigLoader.loadConfig();
            AgentConfig agentConfig = AgentConfig.fromConfig(config);
            MessageBus bus = new MessageBus();
            SessionManager sessionManager = new SessionManager(ConfigLoader.getSessionsDir());
            AgentLoop agent = new AgentLoop(
                    bus,
                    ProviderFactory.fromConfig(config),
                    agentConfig,
                    sessionManager,
                    cronService);
            
            // 【设置回调函数】让定时任务可以调用Agent
            cronService.setOnJob(cronJob -> {
                String msg = cronJob.getMessage();
                String response = agent.processDirect(msg, "cron", cronJob.getId(), null);
                log.info("\n【Agent回复】\n{}", response);
            });
            
            // 执行任务
            boolean executed = cronService.runJob(jobId, force);
            if (executed) {
                log.info("\n任务执行完成");
            } else {
                log.error("错误：任务执行失败（任务可能被禁用，使用 --force 强制执行）");
                System.exit(1);
            }
            
            agent.stop();
        }
    }

    /**
     * 查看服务状态
     */
    @CommandLine.Command(name = "status", description = "查看定时任务服务状态")
    public static class StatusCommand implements Runnable {

        @Override
        public void run() {
            Path dataDir = ConfigLoader.getDataDir();
            WheelCronService cronService = new WheelCronService(dataDir);
            
            Map<String, Object> status = cronService.status();
            
            log.info("定时任务服务状态：");
            log.info("  运行状态: {}", Boolean.TRUE.equals(status.get("enabled")) ? "运行中" : "已停止");
            log.info("  任务总数: {}", status.get("jobs"));
            
            Long nextWake = (Long) status.get("nextWakeAtMs");
            if (nextWake != null) {
                log.info("  下次唤醒: {}", Instant.ofEpochMilli(nextWake));
            } else {
                log.info("  下次唤醒: 无（没有待执行的任务）");
            }
            
            log.info("");
            log.info("提示：使用 'cron list' 查看详细任务列表");
        }
    }
}
