package com.simpleclaw.db;

import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.function.Consumer;

/**
 * 【SQLite 数据库管理器】
 *
 * 提供统一的数据库连接管理和基础操作：
 * - 自动管理连接池（单连接模式，适合嵌入式场景）
 * - 提供便捷的 CRUD 操作方法
 * - 支持事务操作
 * - 自动创建数据库文件
 */
@Slf4j
public class Database {

    private final String dbUrl;
    private final Object lock = new Object();

    /**
     * 创建数据库实例
     * @param dbPath 数据库文件路径（如 "data/app.db"）
     */
    public Database(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
    }

    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    /**
     * 执行无返回值的 SQL 操作
     * @param sql SQL 语句
     * @param params 参数
     * @return 影响的行数
     */
    public int execute(String sql, Object... params) {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                setParams(pstmt, params);
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("执行失败: {}", e.getMessage());
                return 0;
            }
        }
    }

    /**
     * 执行查询操作
     * @param sql SQL 语句
     * @param params 参数
     * @param consumer 结果集消费者
     */
    public void query(String sql, Object[] params, ResultSetConsumer consumer) {
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (params != null) {
                setParams(pstmt, params);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                consumer.accept(rs);
            }
        } catch (SQLException e) {
            log.error("查询失败: {}", e.getMessage());
        }
    }

    /**
     * 执行插入操作并返回生成的主键
     * @param sql SQL 语句
     * @param params 参数
     * @return 生成的主键，失败返回 -1
     */
    public long insertAndGetId(String sql, Object... params) {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                setParams(pstmt, params);
                pstmt.executeUpdate();
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                log.error("插入失败: {}", e.getMessage());
            }
            return -1;
        }
    }

    /**
     * 执行事务操作
     * @param transaction 事务回调
     */
    public void transaction(TransactionCallback transaction) {
        synchronized (lock) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    transaction.execute(conn);
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    log.error("事务回滚: {}", e.getMessage());
                }
            } catch (SQLException e) {
                log.error("事务失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 执行建表语句
     * @param sql CREATE TABLE 语句
     */
    public void createTable(String sql) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("创建表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查表是否存在
     * @param tableName 表名
     */
    public boolean tableExists(String tableName) {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 设置 PreparedStatement 参数
     */
    private void setParams(PreparedStatement pstmt, Object... params) throws SQLException {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                Object param = params[i];
                int idx = i + 1;
                if (param == null) {
                    pstmt.setNull(idx, Types.NULL);
                } else if (param instanceof String) {
                    pstmt.setString(idx, (String) param);
                } else if (param instanceof Integer) {
                    pstmt.setInt(idx, (Integer) param);
                } else if (param instanceof Long) {
                    pstmt.setLong(idx, (Long) param);
                } else if (param instanceof Double) {
                    pstmt.setDouble(idx, (Double) param);
                } else if (param instanceof Boolean) {
                    pstmt.setInt(idx, ((Boolean) param) ? 1 : 0);
                } else if (param instanceof byte[]) {
                    pstmt.setBytes(idx, (byte[]) param);
                } else {
                    pstmt.setString(idx, param.toString());
                }
            }
        }
    }

    /**
     * 结果集消费者接口（可抛出 SQLException）
     */
    @FunctionalInterface
    public interface ResultSetConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    /**
     * 事务回调接口
     */
    @FunctionalInterface
    public interface TransactionCallback {
        void execute(Connection conn) throws SQLException;
    }
}
