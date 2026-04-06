package com.simpleclaw.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 【简化版定时任务】
 *
 * 只保留必要字段：
 * - id: 任务ID
 * - message: 要执行的消息内容
 * - executeAt: 执行时间戳（毫秒）
 * - intervalMs: 循环间隔（毫秒），为0表示只执行一次
 * - enabled: 是否启用
 * - channel: 结果发送渠道（如 weixin, cli）
 * - chatId: 结果接收者ID
 *
 * 【注意】channel 和 chatId 用于任务执行时保持与会话的关联，
 * 确保任务结果能发送到正确的渠道和用户。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimpleJob implements Delayed {

    private String id;
    private String message;
    private long executeAt;  // 下次执行时间
    private long intervalMs; // 循环间隔，0表示只执行一次
    private boolean enabled;
    private String channel;  // 结果发送渠道
    private String chatId;   // 结果接收者ID

    private transient Object scheduledFuture; // 用于取消调度（不序列化）

    public SimpleJob() {}

    public SimpleJob(String id, String message, long executeAt, long intervalMs) {
        this(id, message, executeAt, intervalMs, null, null);
    }

    public SimpleJob(String id, String message, long executeAt, long intervalMs, String channel, String chatId) {
        this.id = id;
        this.message = message;
        this.executeAt = executeAt;
        this.intervalMs = intervalMs;
        this.enabled = true;
        this.channel = channel;
        this.chatId = chatId;
    }

    // 实现 Delayed 接口
    @Override
    public long getDelay(TimeUnit unit) {
        long delay = executeAt - System.currentTimeMillis();
        return unit.convert(Math.max(delay, 0), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        return Long.compare(this.executeAt, ((SimpleJob) o).executeAt);
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getExecuteAt() { return executeAt; }
    public void setExecuteAt(long executeAt) { this.executeAt = executeAt; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public Object getScheduledFuture() { return scheduledFuture; }
    public void setScheduledFuture(Object scheduledFuture) { this.scheduledFuture = scheduledFuture; }
}
