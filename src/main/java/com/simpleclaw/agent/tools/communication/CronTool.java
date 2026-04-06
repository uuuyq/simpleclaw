package com.simpleclaw.agent.tools.communication;

import com.simpleclaw.agent.tools.BaseTool;
import com.simpleclaw.cron.SimpleJob;
import com.simpleclaw.cron.WheelCronService;

import java.time.Instant;
import java.util.*;

/**
 * 【定时任务管理工具】
 *
 * 功能说明：
 * 此工具允许AI Agent动态创建、删除、查询定时任务，实现自我调度和自动化。
 * Agent可以通过此工具为自己设置提醒、定期任务、自动化工作流等。
 *
 * 使用场景：
 * 1. 用户说"每天早上9点提醒我查看邮件" -> Agent调用add_job创建定时任务
 * 2. 用户说"取消刚才设置的提醒" -> Agent调用remove_job删除任务
 * 3. 用户说"查看所有定时任务" -> Agent调用list_jobs列出任务
 * 4. 用户说"暂停每日报告" -> Agent调用disable_job禁用任务
 *
 * 支持的调度类型：
 * - at: 一次性执行，指定具体时间（ISO格式）
 * - every: 周期性执行，指定间隔（毫秒）
 * - cron: Cron表达式执行，支持复杂调度规则
 *
 * 数据存储：
 * 所有定时任务持久化存储在 SQLite 数据库中，
 * 即使系统重启，任务也不会丢失。
 */
public class CronTool extends BaseTool {

    private final WheelCronService cronService;

    /**
     * 构造函数，接收共享的 CronService 实例
     * @param cronService 定时任务服务实例
     */
    public CronTool(WheelCronService cronService) {
        this.cronService = cronService;
    }

    /**
     * 【获取工具名称】
     * 此名称用于LLM识别和调用工具
     */
    @Override
    public String getName() {
        return "cron";
    }

    /**
     * 【获取工具描述】
     * 采用 OpenClaw 的 cron 工具描述风格。
     */
    @Override
    public String getDescription() {
        return "Manage cron jobs and wake events (use for reminders; " +
               "when scheduling a reminder, write the systemEvent text as something that " +
               "will read like a reminder when it fires, and mention that it is a reminder " +
               "depending on the time gap between setting and firing; " +
               "include recent context in reminder text if appropriate).";
    }

    /**
     * 【获取工具参数定义】
     * 定义工具需要的参数结构，供LLM理解和生成正确的调用
     */
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        // 定义properties
        Map<String, Object> properties = new HashMap<>();
        
        // action参数：指定要执行的操作
        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("enum", Arrays.asList("add_job", "remove_job", "list_jobs", "enable_job", "disable_job", "get_job"));
        action.put("description", "要执行的操作：add_job（添加任务）、remove_job（删除任务）、list_jobs（列出任务）、enable_job（启用任务）、disable_job（禁用任务）、get_job（获取任务详情）");
        properties.put("action", action);
        
        // name参数：任务名称（用于add_job）
        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "【add_job必需】任务名称，用于标识和描述任务用途，如'每日邮件提醒'");
        properties.put("name", name);
        
        // schedule_type参数：调度类型（用于add_job）
        Map<String, Object> scheduleType = new HashMap<>();
        scheduleType.put("type", "string");
        scheduleType.put("enum", Arrays.asList("at", "every", "cron"));
        scheduleType.put("description", "【add_job必需】调度类型：at（一次性执行）、every（周期性执行）、cron（Cron表达式）");
        properties.put("schedule_type", scheduleType);
        
        // schedule_value参数：调度值（用于add_job）
        Map<String, Object> scheduleValue = new HashMap<>();
        scheduleValue.put("type", "string");
        scheduleValue.put("description", "【add_job必需】调度值。at类型使用ISO时间格式如'2024-12-25T09:00:00Z'；every类型使用毫秒数如'3600000'（1小时）；cron类型使用表达式如'0 9 * * *'（每天9点）");
        properties.put("schedule_value", scheduleValue);
        
        // timezone参数：时区（可选，用于cron类型）
        Map<String, Object> timezone = new HashMap<>();
        timezone.put("type", "string");
        timezone.put("description", "【add_job可选】时区，用于cron类型，如'Asia/Shanghai'。默认为系统时区");
        properties.put("timezone", timezone);
        
        // message参数：任务消息（用于add_job）
        Map<String, Object> message = new HashMap<>();
        message.put("type", "string");
        message.put("description", "【add_job必需】任务到期时发送给Agent的消息内容。Agent会根据此消息执行相应操作");
        properties.put("message", message);
        
        // deliver参数：是否发送结果（可选，用于add_job）
        Map<String, Object> deliver = new HashMap<>();
        deliver.put("type", "boolean");
        deliver.put("description", "【add_job可选】任务执行后是否将结果发送给用户。默认为false");
        properties.put("deliver", deliver);
        
        // channel参数：发送频道（可选，用于add_job）
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "string");
        channel.put("description", "【add_job可选，deliver=true时必需】发送结果的频道，如'dingtalk'、'email'、'cli'");
        properties.put("channel", channel);
        
        // to参数：接收者（可选，用于add_job）
        Map<String, Object> to = new HashMap<>();
        to.put("type", "string");
        to.put("description", "【add_job可选，deliver=true时必需】接收者ID，如用户ID、邮箱地址等");
        properties.put("to", to);
        
        // delete_after_run参数：执行后删除（可选，用于add_job的at类型）
        Map<String, Object> deleteAfterRun = new HashMap<>();
        deleteAfterRun.put("type", "boolean");
        deleteAfterRun.put("description", "【add_job可选】一次性任务（at类型）执行后是否自动删除。默认为false");
        properties.put("delete_after_run", deleteAfterRun);
        
        // job_id参数：任务ID（用于remove_job、enable_job、disable_job、get_job）
        Map<String, Object> jobId = new HashMap<>();
        jobId.put("type", "string");
        jobId.put("description", "【remove_job/enable_job/disable_job/get_job必需】任务ID，如'abc123'");
        properties.put("job_id", jobId);
        
        // include_disabled参数：是否包含已禁用任务（可选，用于list_jobs）
        Map<String, Object> includeDisabled = new HashMap<>();
        includeDisabled.put("type", "boolean");
        includeDisabled.put("description", "【list_jobs可选】是否列出已禁用的任务。默认为false");
        properties.put("include_disabled", includeDisabled);
        
        params.put("properties", properties);
        
        // 定义required字段
        params.put("required", Collections.singletonList("action"));
        
        return params;
    }

    /**
     * 【工具执行入口】
     * 根据action参数分发到不同的处理方法
     * 
     * @param params 参数映射，包含action和其他相关参数
     * @return 执行结果字符串
     */
    @Override
    public String execute(Map<String, Object> params) {
        // 【步骤1】获取action参数
        String action = getStringParam(params, "action");
        if (action == null || action.isEmpty()) {
            return "[Error: 必需参数 'action' 缺失]";
        }

        // 【步骤2】根据action分发处理
        try {
            switch (action) {
                case "add_job":
                    return addJob(params);
                case "remove_job":
                    return removeJob(params);
                case "list_jobs":
                    return listJobs(params);
                case "enable_job":
                    return enableJob(params, true);
                case "disable_job":
                    return enableJob(params, false);
                case "get_job":
                    return getJob(params);
                default:
                    return "[Error: 未知的操作 '" + action + "']";
            }
        } catch (Exception e) {
            return "[Error: 执行失败 - " + e.getMessage() + "]";
        }
    }

    /**
     * 【添加定时任务】
     *
     * 处理流程：
     * 1. 验证必需参数（name、schedule_type、schedule_value、message）
     * 2. 根据schedule_type创建对应的CronSchedule
     * 3. 解析可选参数（deliver、channel、to、delete_after_run）
     * 4. 调用cronService.addJob创建任务
     * 5. 返回创建成功的任务信息
     *
     * @param params 参数映射
     * @return 执行结果
     */
    private String addJob(Map<String, Object> params) {
        // 【验证必需参数】
        String scheduleType = getStringParam(params, "schedule_type");
        String scheduleValue = getStringParam(params, "schedule_value");
        String message = getStringParam(params, "message");
        
        if (scheduleType == null || scheduleType.isEmpty()) {
            return "[Error: add_job 必需参数 'schedule_type'（调度类型：at/every）]";
        }
        if (scheduleValue == null || scheduleValue.isEmpty()) {
            return "[Error: add_job 必需参数 'schedule_value'（调度值）]";
        }
        if (message == null || message.isEmpty()) {
            return "[Error: add_job 必需参数 'message'（任务消息）]";
        }
        
        // 【创建调度配置】
        String schedule;
        try {
            switch (scheduleType.toLowerCase()) {
                case "at":
                    // at类型：解析ISO时间格式为时间戳
                    Instant instant = Instant.parse(scheduleValue);
                    long executeAt = instant.toEpochMilli();
                    // 【最小延迟保护】确保任务至少在未来3秒后执行，避免立即执行
                    long minDelay = 3000; // 3秒
                    long now = System.currentTimeMillis();
                    if (executeAt < now + minDelay) {
                        executeAt = now + minDelay;
                    }
                    schedule = String.valueOf(executeAt);
                    break;
                    
                case "every":
                    // every类型：解析毫秒数
                    long intervalMs = Long.parseLong(scheduleValue);
                    if (intervalMs <= 0) {
                        return "[Error: every类型的间隔必须大于0毫秒]";
                    }
                    schedule = String.valueOf(intervalMs);
                    break;
                    
                default:
                    return "[Error: 未知的schedule_type '" + scheduleType + "'，可选值：at/every]";
            }
        } catch (Exception e) {
            return "[Error: 调度参数解析失败 - " + e.getMessage() + "]";
        }
        
        // 【解析可选参数】
        String channel = getStringParam(params, "channel");
        String to = getStringParam(params, "to");
        boolean deliver = getBooleanParam(params, "deliver", false);

        // 如果没有指定channel/to，尝试从请求上下文获取（框架自动注入的 _channel 和 _chatId）
        if (channel == null) {
            channel = getStringParam(params, "_channel");
        }
        if (to == null) {
            to = getStringParam(params, "_chatId");
        }

        // 如果指定了deliver=true但没有指定channel/to，使用默认值
        if (deliver && channel == null) {
            channel = "cli"; // 默认发送到命令行
        }

        // 【创建任务】
        try {
            SimpleJob job = cronService.addJob(message, schedule, message, deliver, channel, to, false);

            // 【构建简洁响应】
            StringBuilder result = new StringBuilder();
            result.append("✅ 定时任务已创建\n");
            result.append("ID: ").append(job.getId()).append("\n");
            result.append("类型: ").append(scheduleType).append("\n");
            result.append("消息: ").append(message);
            return result.toString();

        } catch (Exception e) {
            return "[Error: 创建任务失败 - " + e.getMessage() + "]";
        }
    }

    /**
     * 【删除定时任务】
     *
     * @param params 参数映射，需要job_id
     * @return 执行结果
     */
    private String removeJob(Map<String, Object> params) {
        String jobId = getStringParam(params, "job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "[Error: remove_job 必需参数 'job_id'（任务ID）]";
        }
        
        // 先获取任务信息用于确认
        Optional<SimpleJob> job = cronService.getJob(jobId);
        if (!job.isPresent()) {
            return "[Error: 未找到ID为 '" + jobId + "' 的任务]";
        }
        
        String jobMessage = job.get().getMessage();
        boolean removed = cronService.removeJob(jobId);
        
        if (removed) {
            return "✅ 任务 '" + jobMessage + "' (" + jobId + ") 已删除";
        } else {
            return "[Error: 删除任务失败]";
        }
    }

    /**
     * 【列出所有定时任务】
     *
     * @param params 参数映射，可选include_disabled
     * @return 格式化的任务列表
     */
    private String listJobs(Map<String, Object> params) {
        boolean includeDisabled = getBooleanParam(params, "include_disabled", false);
        List<SimpleJob> jobs = cronService.listJobs(includeDisabled);
        
        if (jobs.isEmpty()) {
            return "📋 当前没有" + (includeDisabled ? "" : "启用的") + "定时任务";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("📋 定时任务列表（共 ").append(jobs.size()).append(" 个）\n");
        result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        result.append(String.format("%-8s %-8s %-30s%n",
                "ID", "状态", "消息"));
        result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        for (SimpleJob job : jobs) {
            String status = job.isEnabled() ? "✅启用" : "❌禁用";
            String message = truncate(job.getMessage(), 28);
            
            result.append(String.format("%-8s %-8s %-30s%n",
                    job.getId(),
                    status,
                    message));
        }
        
        result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return result.toString();
    }

    /**
     * 【启用或禁用定时任务】
     *
     * @param params 参数映射，需要job_id
     * @param enabled true=启用，false=禁用
     * @return 执行结果
     */
    private String enableJob(Map<String, Object> params, boolean enabled) {
        String actionName = enabled ? "enable_job" : "disable_job";
        String jobId = getStringParam(params, "job_id");
        
        if (jobId == null || jobId.isEmpty()) {
            return "[Error: " + actionName + " 必需参数 'job_id'（任务ID）]";
        }
        
        Optional<SimpleJob> job = cronService.enableJob(jobId, enabled);
        
        if (job.isPresent()) {
            String statusText = enabled ? "启用" : "禁用";
            return "✅ 任务 '" + job.get().getMessage() + "' (" + jobId + ") 已" + statusText;
        } else {
            return "[Error: 未找到ID为 '" + jobId + "' 的任务]";
        }
    }

    /**
     * 【获取单个任务详情】
     *
     * @param params 参数映射，需要job_id
     * @return 任务详细信息
     */
    private String getJob(Map<String, Object> params) {
        String jobId = getStringParam(params, "job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "[Error: get_job 必需参数 'job_id'（任务ID）]";
        }
        
        Optional<SimpleJob> jobOpt = cronService.getJob(jobId);
        if (!jobOpt.isPresent()) {
            return "[Error: 未找到ID为 '" + jobId + "' 的任务]";
        }
        
        SimpleJob job = jobOpt.get();
        StringBuilder result = new StringBuilder();
        result.append("📋 任务详情\n");
        result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        result.append("任务ID: ").append(job.getId()).append("\n");
        result.append("状态: ").append(job.isEnabled() ? "✅ 启用" : "❌ 禁用").append("\n");
        result.append("任务消息: ").append(job.getMessage()).append("\n");
        
        result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return result.toString();
    }

    // ========== 辅助方法 ==========

    /**
     * 【获取字符串参数】
     * 从参数映射中安全地获取字符串值
     */
    private String getStringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    /**
     * 【获取布尔参数】
     * 从参数映射中安全地获取布尔值，支持字符串和布尔类型
     */
    private boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 【截断字符串】
     * 将长字符串截断到指定长度
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 2) + ".." : s;
    }

}
