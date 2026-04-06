package com.simpleclaw.cron;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 【基于延迟队列的定时任务服务】
 *
 * 使用 DelayQueue 实现精确的定时任务调度：
 * - 任务到精确时间点才从队列取出执行
 * - 支持一次性任务和循环任务
 * - SQLite 数据库持久化
 */
@Slf4j
public class WheelCronService implements CronService {

    private final JobStore jobStore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor;

    // 任务存储（内存缓存）
    private final Map<String, SimpleJob> jobs = new ConcurrentHashMap<>();
    // 延迟队列
    private final DelayQueue<SimpleJob> delayQueue;

    private volatile CronJobCallback callback;

    public WheelCronService(Path dataDir) {
        // 确保数据目录存在
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            // ignore
        }
        this.jobStore = new JobStore(dataDir.resolve("cron.db").toString());
        this.delayQueue = new DelayQueue<>();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cron-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            loadJobs();
            // 将任务放入延迟队列
            for (SimpleJob job : jobs.values()) {
                if (job.isEnabled()) {
                    delayQueue.offer(job);
                }
            }
            // 启动消费者线程
            startConsumerThread();
            log.info("定时服务已启动，队列任务数: {}", delayQueue.size());
        }
    }

    /**
     * 从 SQLite 加载任务
     */
    private void loadJobs() {
        List<SimpleJob> list = jobStore.findAll();
        jobs.clear();
        for (SimpleJob job : list) {
            jobs.put(job.getId(), job);
        }
    }

    /**
     * 消费者线程：从延迟队列取出到期任务执行
     */
    private void startConsumerThread() {
        executor.submit(() -> {
            while (running.get()) {
                try {
                    SimpleJob job = delayQueue.take();
                    if (job != null && job.isEnabled()) {
                        executeJob(job);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * 执行任务
     */
    private void executeJob(SimpleJob job) {
        log.info("[Cron] 执行任务: " + job.getId() + " - " + job.getMessage());
        if (callback != null) {
            try {
                callback.onJob(job);
            } catch (Exception e) {
                log.info("[Cron] 任务执行失败: " + job.getId() + ", 错误: " + e.getMessage());
            }
        }

        // 循环任务重新调度
        if (job.getIntervalMs() > 0 && job.isEnabled()) {
            job.setExecuteAt(System.currentTimeMillis() + job.getIntervalMs());
            jobStore.save(job);  // 更新数据库
            delayQueue.offer(job);
        } else {
            jobs.remove(job.getId());
            jobStore.delete(job.getId());  // 从数据库删除
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            executor.shutdown();
            log.info("定时服务已停止");
        }
    }

    @Override
    public void setOnJob(CronJobCallback callback) {
        this.callback = callback;
    }

    // ========== 任务管理 ==========

    public String addOnce(String message, long executeAt) {
        return addOnce(message, executeAt, null, null);
    }

    public String addOnce(String message, long executeAt, String channel, String chatId) {
        String id = generateId();
        SimpleJob job = new SimpleJob(id, message, executeAt, 0, channel, chatId);
        jobs.put(id, job);
        jobStore.save(job);
        if (running.get()) {
            delayQueue.offer(job);
        }
        log.info("添加一次性任务: {}, 执行时间: {}", id, new Date(executeAt));
        return id;
    }

    public String addRecurring(String message, long intervalMs) {
        return addRecurring(message, intervalMs, null, null);
    }

    public String addRecurring(String message, long intervalMs, String channel, String chatId) {
        String id = generateId();
        long executeAt = System.currentTimeMillis() + intervalMs;
        SimpleJob job = new SimpleJob(id, message, executeAt, intervalMs, channel, chatId);
        jobs.put(id, job);
        jobStore.save(job);
        if (running.get()) {
            delayQueue.offer(job);
        }
        log.info("添加循环任务: {}, 间隔: {}ms", id, intervalMs);
        return id;
    }

    public boolean remove(String id) {
        SimpleJob job = jobs.remove(id);
        if (job != null) {
            job.setEnabled(false);
            jobStore.delete(id);  // 从数据库删除
            log.info("删除任务: {}", id);
            return true;
        }
        return false;
    }

    public boolean enable(String id, boolean enabled) {
        SimpleJob job = jobs.get(id);
        if (job != null) {
            job.setEnabled(enabled);
            jobStore.updateEnabled(id, enabled);  // 更新数据库
            if (enabled && running.get()) {
                delayQueue.offer(job);
            }
            return true;
        }
        return false;
    }

    public List<SimpleJob> list() {
        return new ArrayList<>(jobs.values());
    }

    public boolean runNow(String id) {
        SimpleJob job = jobs.get(id);
        if (job != null && callback != null) {
            executor.submit(() -> executeJob(job));
            return true;
        }
        return false;
    }

    private String generateId() {
        return Long.toHexString(System.currentTimeMillis()).substring(5);
    }

    // ========== CronService 接口实现 ==========

    @Override
    public List<SimpleJob> listJobs(boolean includeDisabled) {
        return jobs.values().stream()
                .filter(j -> includeDisabled || j.isEnabled())
                .collect(Collectors.toList());
    }

    @Override
    public SimpleJob addJob(String name, String schedule, String message, boolean deliver, String channel, String to, boolean deleteAfterRun) {
        try {
            long value = Long.parseLong(schedule);
            String id;
            if (value < 1000000000000L) {
                id = addRecurring(message, value, channel, to);
            } else {
                id = addOnce(message, value, channel, to);
            }
            return jobs.get(id);
        } catch (NumberFormatException e) {
            String id = addOnce(message, System.currentTimeMillis() + 3600000, channel, to);
            return jobs.get(id);
        }
    }

    @Override
    public boolean removeJob(String jobId) {
        return remove(jobId);
    }

    @Override
    public Optional<SimpleJob> enableJob(String jobId, boolean enabled) {
        if (enable(jobId, enabled)) {
            return Optional.of(jobs.get(jobId));
        }
        return Optional.empty();
    }

    @Override
    public boolean runJob(String jobId, boolean force) {
        return runNow(jobId);
    }

    @Override
    public Optional<SimpleJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", running.get());
        status.put("jobs", jobs.size());
        status.put("enabledJobs", jobs.values().stream().filter(SimpleJob::isEnabled).count());
        return status;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}