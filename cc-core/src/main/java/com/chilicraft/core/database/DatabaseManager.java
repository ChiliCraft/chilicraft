package com.chilicraft.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 数据库子系统：HikariCP 连接池 + SQLite（WAL 模式）。
 *
 * <p>线程模型：所有写操作提交到内部单线程 executor 串行执行
 * （SQLite 单写者模型，配合 WAL 消除写写竞争）；登录预载等只读
 * 操作可通过 {@link #openConnection()} 并发读取（WAL 允许并发读）。
 * 任何写路径禁止在主线程同步执行。</p>
 *
 * <p>关停顺序（由主类 onDisable 调用 {@link #shutdown()}）：
 * 先停 executor 并等待剩余写任务冲刷完成，最后才关闭连接池。</p>
 */
public final class DatabaseManager {

    /** 关停时等待剩余写任务冲刷的上限 */
    private static final long SHUTDOWN_TIMEOUT_MS = 10_000;

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final Logger logger;

    public DatabaseManager(Path dbFile, Logger logger) {
        this.logger = logger;

        // 1. 确保数据目录存在
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据库目录: " + dbFile.getParent(), e);
        }

        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString().replace('\\', '/');

        // 2. 直连完成持久 PRAGMA 与模式迁移（服务器启动阶段，允许阻塞）
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            // WAL 是数据库文件的持久属性，设置一次永久生效：
            // 读写可并发，配合 busy_timeout 消除大部分锁冲突
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            SchemaMigrations.migrate(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("数据库初始化失败: " + url, e);
        }

        // 3. 连接池（SQLite 单写者模型下池无需大）
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("cc-core-sqlite");
        hikari.setJdbcUrl(url);
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10_000);
        hikari.setMaxLifetime(0); // SQLite 场景无需定期回收连接
        // busy_timeout 必须对每个物理连接生效
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        this.dataSource = new HikariDataSource(hikari);

        // 4. 单线程写队列：串行化全部写访问
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cc-core-db-writer");
            t.setDaemon(false); // 非守护：确保关停冲刷完成
            return t;
        });
    }

    /**
     * 提交查询任务到 DB 线程串行执行。
     * 供档案异步落库等场景使用；任务内部自行处理连接开关。
     */
    public <T> CompletableFuture<T> supply(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    /**
     * 提交写任务到 DB 线程串行执行。
     * 任务抛出的异常在此统一记录日志，不向主流程扩散。
     */
    public CompletableFuture<Void> write(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("数据库写入任务失败", e);
            }
        }, executor);
    }

    /**
     * 从池中取连接。
     * 适用于：登录预载等异步只读场景、以及 DB 线程内的写任务。
     * 调用方负责 close。
     */
    public Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 关停：等待剩余写任务冲刷（上限 10s）→ 关闭连接池。 */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("DB 写线程在 {}ms 内未能完成剩余任务，仍有数据可能未落盘", SHUTDOWN_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("等待 DB 写线程完成时被中断");
        }
        dataSource.close();
        logger.info("数据库连接池已关闭");
    }
}
