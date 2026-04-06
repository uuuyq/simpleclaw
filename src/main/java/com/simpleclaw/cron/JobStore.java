package com.simpleclaw.cron;

import com.simpleclaw.db.Database;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 【定时任务存储】
 *
 * 使用 SQLite 数据库持久化定时任务
 */
@Slf4j
public class JobStore {

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS cron_jobs (" +
            "id TEXT PRIMARY KEY," +
            "message TEXT NOT NULL," +
            "execute_at INTEGER NOT NULL," +
            "interval_ms INTEGER NOT NULL DEFAULT 0," +
            "enabled INTEGER NOT NULL DEFAULT 1," +
            "channel TEXT," +
            "chat_id TEXT" +
            ")";

    private final Database db;

    public JobStore(String dbPath) {
        this.db = new Database(dbPath);
        db.createTable(CREATE_TABLE_SQL);
    }

    /**
     * 保存任务（新增或更新）
     */
    public void save(SimpleJob job) {
        String sql = "INSERT OR REPLACE INTO cron_jobs (id, message, execute_at, interval_ms, enabled, channel, chat_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        int rows = db.execute(sql,
                job.getId(),
                job.getMessage(),
                job.getExecuteAt(),
                job.getIntervalMs(),
                job.isEnabled() ? 1 : 0,
                job.getChannel(),
                job.getChatId()
        );
        log.debug("保存任务 {}, 影响行数: {}", job.getId(), rows);
    }

    /**
     * 删除任务
     */
    public boolean delete(String id) {
        String sql = "DELETE FROM cron_jobs WHERE id = ?";
        return db.execute(sql, id) > 0;
    }

    /**
     * 查找任务
     */
    public Optional<SimpleJob> findById(String id) {
        final SimpleJob[] result = new SimpleJob[1];
        db.query("SELECT * FROM cron_jobs WHERE id = ?", new Object[]{id}, rs -> {
            if (rs.next()) {
                result[0] = mapRow(rs);
            }
        });
        return Optional.ofNullable(result[0]);
    }

    /**
     * 查询所有任务
     */
    public List<SimpleJob> findAll() {
        List<SimpleJob> jobs = new ArrayList<>();
        db.query("SELECT * FROM cron_jobs", null, rs -> {
            while (rs.next()) {
                jobs.add(mapRow(rs));
            }
        });
        return jobs;
    }

    /**
     * 更新任务状态
     */
    public boolean updateEnabled(String id, boolean enabled) {
        String sql = "UPDATE cron_jobs SET enabled = ? WHERE id = ?";
        return db.execute(sql, enabled ? 1 : 0, id) > 0;
    }

    /**
     * 更新任务执行时间
     */
    public boolean updateExecuteAt(String id, long executeAt) {
        String sql = "UPDATE cron_jobs SET execute_at = ? WHERE id = ?";
        return db.execute(sql, executeAt, id) > 0;
    }

    /**
     * 统计任务数量
     */
    public int count() {
        final int[] result = {0};
        db.query("SELECT COUNT(*) FROM cron_jobs", null, rs -> {
            if (rs.next()) {
                result[0] = rs.getInt(1);
            }
        });
        return result[0];
    }

    /**
     * 统计启用任务数量
     */
    public int countEnabled() {
        final int[] result = {0};
        db.query("SELECT COUNT(*) FROM cron_jobs WHERE enabled = 1", null, rs -> {
            if (rs.next()) {
                result[0] = rs.getInt(1);
            }
        });
        return result[0];
    }

    /**
     * 映射结果集到 SimpleJob
     */
    private SimpleJob mapRow(ResultSet rs) throws SQLException {
        SimpleJob job = new SimpleJob();
        job.setId(rs.getString("id"));
        job.setMessage(rs.getString("message"));
        job.setExecuteAt(rs.getLong("execute_at"));
        job.setIntervalMs(rs.getLong("interval_ms"));
        job.setEnabled(rs.getInt("enabled") == 1);
        job.setChannel(rs.getString("channel"));
        job.setChatId(rs.getString("chat_id"));
        return job;
    }
}
