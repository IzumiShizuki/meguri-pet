package com.meguri.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs one non-overlapping poll loop and owns the worker resources it drives. */
public final class SafePollingLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafePollingLifecycle.class);

    private final String workerName;
    private final Runnable poll;
    private final AutoCloseable resource;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public SafePollingLifecycle(String workerName, Runnable poll,
                                AutoCloseable resource, Duration interval) {
        this.workerName = required(workerName);
        this.poll = Objects.requireNonNull(poll, "poll");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.interval = positive(interval);
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, workerName + "-poll");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pollSafely, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        if (!running.get()) return;
        try {
            poll.run();
        } catch (Throwable error) {
            LOGGER.error("Background worker {} poll failed; polling will continue", workerName, error);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
            try {
                current.awaitTermination(Math.min(5000L, Math.max(100L, interval.toMillis())),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            resource.close();
        } catch (Exception error) {
            LOGGER.warn("Background worker {} did not close cleanly", workerName, error);
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workerName must not be blank");
        return value.trim();
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() < 1L) {
            throw new IllegalArgumentException("poll interval must be at least one millisecond");
        }
        return value;
    }
}
