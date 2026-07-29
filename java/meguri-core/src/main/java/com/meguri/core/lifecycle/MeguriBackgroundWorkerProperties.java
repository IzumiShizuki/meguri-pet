package com.meguri.core.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("meguri.background-workers")
public class MeguriBackgroundWorkerProperties {
    private final Memory memory = new Memory();
    private final Outbox outbox = new Outbox();
    private final Context context = new Context();

    public Memory getMemory() {
        return memory;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Context getContext() {
        return context;
    }

    public static final class Memory {
        private boolean enabled = true;
        private String storeMode = "auto";
        private boolean initializeSchema = true;
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration lease = Duration.ofSeconds(30);
        private Duration writeTimeout = Duration.ofSeconds(20);
        private Duration initialBackoff = Duration.ofSeconds(1);
        private int batchSize = 32;
        private int maxAttempts = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStoreMode() { return storeMode; }
        public void setStoreMode(String storeMode) { this.storeMode = required(storeMode, "memory.storeMode"); }
        public boolean isInitializeSchema() { return initializeSchema; }
        public void setInitializeSchema(boolean initializeSchema) { this.initializeSchema = initializeSchema; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration value) { pollInterval = positive(value, "memory.pollInterval"); }
        public Duration getLease() { return lease; }
        public void setLease(Duration value) { lease = positive(value, "memory.lease"); }
        public Duration getWriteTimeout() { return writeTimeout; }
        public void setWriteTimeout(Duration value) { writeTimeout = positive(value, "memory.writeTimeout"); }
        public Duration getInitialBackoff() { return initialBackoff; }
        public void setInitialBackoff(Duration value) { initialBackoff = positive(value, "memory.initialBackoff"); }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = positive(value, "memory.batchSize"); }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { maxAttempts = positive(value, "memory.maxAttempts"); }
    }

    public static final class Outbox {
        private boolean enabled = true;
        private Duration pollInterval = Duration.ofMillis(250);
        private Duration lease = Duration.ofSeconds(30);
        private int batchSize = 64;
        private int deadLetterAfter = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration value) { pollInterval = positive(value, "outbox.pollInterval"); }
        public Duration getLease() { return lease; }
        public void setLease(Duration value) { lease = positive(value, "outbox.lease"); }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = positive(value, "outbox.batchSize"); }
        public int getDeadLetterAfter() { return deadLetterAfter; }
        public void setDeadLetterAfter(int value) { deadLetterAfter = positive(value, "outbox.deadLetterAfter"); }
    }

    public static final class Context {
        private boolean enabled = true;
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration lease = Duration.ofSeconds(30);
        private Duration retryBackoff = Duration.ofSeconds(2);
        private int batchSize = 16;
        private int maxAttempts = 5;
        private int maximumSummaryCharacters = 4_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration value) { pollInterval = positive(value, "context.pollInterval"); }
        public Duration getLease() { return lease; }
        public void setLease(Duration value) { lease = positive(value, "context.lease"); }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration value) { retryBackoff = positive(value, "context.retryBackoff"); }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = positive(value, "context.batchSize"); }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { maxAttempts = positive(value, "context.maxAttempts"); }
        public int getMaximumSummaryCharacters() { return maximumSummaryCharacters; }
        public void setMaximumSummaryCharacters(int value) {
            maximumSummaryCharacters = positive(value, "context.maximumSummaryCharacters");
        }
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
