package com.simpleclaw.cron;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【简化版 Cron 服务接口】
 * 
 * 基于时间轮的简化定时任务服务，只使用 SimpleJob 模型。
 */
public interface CronService {

    /** 设置任务执行回调 */
    void setOnJob(CronJobCallback callback);

    /** 启动服务 */
    void start();

    /** 停止服务 */
    void stop();

    /** 是否运行中 */
    boolean isRunning();

    // ========== 任务管理 ==========

    /** 列出所有任务 */
    List<SimpleJob> listJobs(boolean includeDisabled);

    default List<SimpleJob> listJobs() {
        return listJobs(false);
    }

    /** 
     * 添加任务
     * @param name 任务名称（保留参数，实际使用message）
     * @param schedule 调度信息：可以是时间戳（一次性）或间隔毫秒数（循环）
     * @param message 任务消息内容
     * @param deliver 是否发送结果
     * @param channel 结果发送渠道
     * @param to 接收者ID
     * @param deleteAfterRun 执行后是否删除
     */
    SimpleJob addJob(String name, String schedule, String message,
                     boolean deliver, String channel, String to, boolean deleteAfterRun);

    default SimpleJob addJob(String name, String schedule, String message) {
        return addJob(name, schedule, message, false, null, null, false);
    }

    /** 删除任务 */
    boolean removeJob(String jobId);

    /** 启用/禁用任务 */
    Optional<SimpleJob> enableJob(String jobId, boolean enabled);

    /** 立即执行任务 */
    boolean runJob(String jobId, boolean force);

    /** 获取任务 */
    Optional<SimpleJob> getJob(String jobId);

    /** 获取状态 */
    Map<String, Object> status();

    /** 任务回调 */
    @FunctionalInterface
    interface CronJobCallback {
        void onJob(SimpleJob job);
    }
}
