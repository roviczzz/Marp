package me.roviczzz.marp.util;

import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main-thread hop helper.
 *
 * <p>Bukkit APIs must run on the primary server thread. Production code uses
 * {@link #bukkit(Plugin)}; unit tests use {@link #direct()} so async chains
 * complete deterministically without a server instance.
 */
public final class Scheduler {

    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    @FunctionalInterface
    public interface DelayedScheduler {
        Cancellable schedule(Runnable task, long delayTicks);
    }

    @FunctionalInterface
    public interface RepeatingScheduler {
        Cancellable schedule(Runnable task, long delayTicks, long periodTicks);
    }

    private final BooleanSupplier isPrimaryThread;
    private final java.util.function.Consumer<Runnable> mainRunner;
    private final java.util.function.Consumer<Runnable> nextTickRunner;
    private final DelayedScheduler delayedScheduler;
    private final RepeatingScheduler repeatingScheduler;

    public Scheduler(
            BooleanSupplier isPrimaryThread,
            java.util.function.Consumer<Runnable> mainRunner,
            java.util.function.Consumer<Runnable> nextTickRunner,
            DelayedScheduler delayedScheduler,
            RepeatingScheduler repeatingScheduler) {
        this.isPrimaryThread = isPrimaryThread;
        this.mainRunner = mainRunner;
        this.nextTickRunner = nextTickRunner;
        this.delayedScheduler = delayedScheduler;
        this.repeatingScheduler = repeatingScheduler;
    }

    public static Scheduler bukkit(Plugin plugin) {
        return new Scheduler(
                Bukkit::isPrimaryThread,
                task -> {
                    if (Bukkit.isPrimaryThread()) {
                        task.run();
                    } else if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, task);
                    }
                },
                task -> {
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, task);
                    }
                },
                (task, delay) -> {
                    if (!plugin.isEnabled()) {
                        return () -> {
                        };
                    }
                    BukkitTask handle = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
                    return handle::cancel;
                },
                (task, delay, period) -> {
                    if (!plugin.isEnabled()) {
                        return () -> {
                        };
                    }
                    BukkitTask handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
                    return handle::cancel;
                });
    }

    /** Synchronous scheduler for unit tests where no Bukkit server exists. */
    public static Scheduler direct() {
        return new Scheduler(
                () -> true,
                Runnable::run,
                Runnable::run,
                (task, delay) -> () -> {
                },
                (task, delay, period) -> () -> {
                });
    }

    public boolean isMainThread() {
        return isPrimaryThread.getAsBoolean();
    }

    public void assertMainThread() {
        if (!isMainThread()) {
            throw new IllegalStateException("Expected main thread, on " + Thread.currentThread().getName());
        }
    }

    public void runOnMain(Runnable task) {
        mainRunner.accept(task);
    }

    public void runNextTick(Runnable task) {
        nextTickRunner.accept(task);
    }

    public Cancellable runLater(Runnable task, long delayTicks) {
        return delayedScheduler.schedule(task, delayTicks);
    }

    public Cancellable runTimer(Runnable task, long delayTicks, long periodTicks) {
        return repeatingScheduler.schedule(task, delayTicks, periodTicks);
    }
}
