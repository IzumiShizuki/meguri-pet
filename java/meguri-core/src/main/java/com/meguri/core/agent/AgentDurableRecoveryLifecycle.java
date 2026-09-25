package com.meguri.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resumes persisted Agent tasks on startup and keeps recovery non-overlapping. */
public final class AgentDurableRecoveryLifecycle implements SmartLifecycle {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AgentDurableRecoveryLifecycle.class);

    private final AgentRuntime runtime;
    private final Duration interval;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public AgentDurableRecoveryLifecycle(
            AgentRuntime runtime, Duration interval, int batchSize) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.interval = positive(interval);
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "meguri-agent-recovery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::recoverSafely, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recoverSafely() {
        if (!running.get()) return;
        try {
            runtime.resumeDurableTasks(batchSize).block();
        } catch (Throwable error) {
            if (!running.get() || Thread.currentThread().isInterrupted()) return;
            LOGGER.error("Agent durable recovery pass failed; polling will continue", error);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) return;
        current.shutdownNow();
        try {
            current.awaitTermination(
                    Math.min(5000L, Math.max(100L, interval.toMillis())),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
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

    private static Duration positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() < 1L) {
            throw new IllegalArgumentException("recovery interval must be at least one millisecond");
        }
        return value;
    }
}
