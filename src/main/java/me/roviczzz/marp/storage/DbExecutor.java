package me.roviczzz.marp.storage;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-threaded executor named {@code Marp-DB}.
 *
 * <p>Every JDBC call in the plugin runs on this thread. SQLite serialises
 * writers anyway, and a single named thread makes thread dumps unambiguous and
 * shutdown bounded.
 */
public final class DbExecutor {

    @FunctionalInterface
    public interface SqlCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws Exception;
    }

    public static final String THREAD_NAME = "Marp-DB";

    private final ExecutorService executor;
    private final AtomicInteger pending = new AtomicInteger();
    private volatile Thread dbThread;

    public DbExecutor() {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(false);
            this.dbThread = thread;
            return thread;
        });
    }

    public <T> CompletableFuture<T> submit(SqlCallable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.call());
                } catch (StorageException exception) {
                    future.completeExceptionally(exception);
                } catch (Exception exception) {
                    future.completeExceptionally(new StorageException("Database work failed", exception));
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.decrementAndGet();
            future.completeExceptionally(new StorageException("Database executor is shut down", exception));
        }
        return future;
    }

    public CompletableFuture<Void> submitRun(SqlRunnable work) {
        return submit(() -> {
            work.run();
            return null;
        });
    }

    public int pendingCount() {
        return pending.get();
    }

    public boolean isDbThread() {
        return Thread.currentThread() == dbThread;
    }

    /**
     * Initiates an orderly shutdown and waits up to {@code timeout} for queued
     * work to finish. Never blocks indefinitely.
     *
     * @return {@code true} if the executor terminated within the bound
     */
    public boolean shutdown(Duration timeout) {
        executor.shutdown();
        try {
            return executor.awaitTermination(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
