package me.roviczzz.marp.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.storage.migration.MigrationRunner;
import me.roviczzz.marp.storage.repo.BackRepository;
import me.roviczzz.marp.storage.repo.HomeRepository;
import me.roviczzz.marp.storage.repo.SqlBackRepository;
import me.roviczzz.marp.storage.repo.SqlHomeRepository;
import me.roviczzz.marp.storage.repo.SqlWarpRepository;
import me.roviczzz.marp.storage.repo.WarpRepository;

/**
 * Owns the SQLite connection, the {@link DbExecutor}, and the repositories.
 *
 * <p>No JDBC call runs outside {@link DbExecutor}. If initialisation fails, any
 * partially opened connection is closed immediately so the database file is not
 * left locked.
 */
public final class SqlStorage {

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final Path databaseFile;
    private final int busyTimeoutMs;
    private final boolean wal;
    private final SqlDialect dialect;
    private final MigrationRunner migrations;
    private final DbExecutor executor;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final HomeRepository homes;
    private final WarpRepository warps;
    private final BackRepository back;

    private volatile Connection connection;

    public SqlStorage(Path databaseFile, int busyTimeoutMs, boolean wal) {
        this(databaseFile, busyTimeoutMs, wal, SqlDialect.SQLITE, MigrationRunner.standard(), new DbExecutor());
    }

    public SqlStorage(
            Path databaseFile,
            int busyTimeoutMs,
            boolean wal,
            SqlDialect dialect,
            MigrationRunner migrations,
            DbExecutor executor) {
        this.databaseFile = databaseFile.toAbsolutePath();
        this.busyTimeoutMs = busyTimeoutMs;
        this.wal = wal;
        this.dialect = dialect;
        this.migrations = migrations;
        this.executor = executor;
        this.homes = new SqlHomeRepository(this);
        this.warps = new SqlWarpRepository(this);
        this.back = new SqlBackRepository(this);
    }

    public CompletableFuture<Void> init() {
        executor.submitRun(() -> {
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try {
                dialect.applyPragmas(opened, busyTimeoutMs, wal);
                migrations.migrate(opened);
                this.connection = opened;
            } catch (Exception exception) {
                try {
                    opened.close();
                } catch (SQLException suppressed) {
                    exception.addSuppressed(suppressed);
                }
                throw exception;
            }
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                ready.completeExceptionally(throwable);
            } else {
                ready.complete(null);
            }
        });
        return ready;
    }

    public CompletableFuture<Void> ready() {
        return ready;
    }

    public boolean isReady() {
        return ready.isDone() && !ready.isCompletedExceptionally() && connection != null;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public DbExecutor executor() {
        return executor;
    }

    public HomeRepository homes() {
        return homes;
    }

    public WarpRepository warps() {
        return warps;
    }

    public BackRepository back() {
        return back;
    }

    public <T> CompletableFuture<T> query(SqlWork<T> work) {
        return executor.submit(() -> work.execute(requireConnection()));
    }

    public <T> CompletableFuture<T> inTransaction(SqlWork<T> work) {
        return executor.submit(() -> {
            Connection conn = requireConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = work.execute(conn);
                conn.commit();
                return result;
            } catch (Exception exception) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    exception.addSuppressed(rollbackError);
                }
                throw exception;
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        });
    }

    /**
     * Checkpoints the WAL, closes the connection on the DB thread, and waits up
     * to {@code timeout} for pending work to drain.
     */
    public boolean shutdown(Duration timeout) {
        if (connection != null) {
            executor.submitRun(() -> {
                Connection conn = this.connection;
                this.connection = null;
                if (conn == null) {
                    return;
                }
                try {
                    if (wal) {
                        dialect.checkpointTruncate(conn);
                    }
                } finally {
                    conn.close();
                }
            });
        }
        boolean terminated = executor.shutdown(timeout);
        Connection lingering = this.connection;
        if (lingering != null) {
            this.connection = null;
            try {
                lingering.close();
            } catch (SQLException ignored) {
            }
        }
        return terminated;
    }

    private Connection requireConnection() {
        if (!executor.isDbThread()) {
            throw new StorageException("JDBC call attempted off the Marp-DB thread");
        }
        Connection conn = this.connection;
        if (conn == null) {
            throw new StorageException("Storage is not open");
        }
        return conn;
    }
}
