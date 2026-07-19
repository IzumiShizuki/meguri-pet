package com.meguri.core.runtime;

import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.TurnRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mutable, thread-safe bookkeeping for one turn. */
public final class TurnRecord {
    private final String turnId;
    private final String traceId;
    private final TurnRequest request;
    private final Instant acceptedAt;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private volatile TurnStatus status = TurnStatus.ACCEPTED;
    private volatile ChatResponse result;
    private volatile String error;

    public TurnRecord(String turnId, String traceId, TurnRequest request) {
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.request = Objects.requireNonNull(request, "request");
        this.acceptedAt = Instant.now();
    }

    public String getTurnId() {
        return turnId;
    }

    public String turnId() {
        return turnId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String traceId() {
        return traceId;
    }

    public TurnRequest getRequest() {
        return request;
    }

    public TurnRequest request() {
        return request;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public TurnStatus getStatus() {
        return status;
    }

    public String statusValue() {
        return status.wireValue();
    }

    public synchronized void setStatus(TurnStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public ChatResponse getResult() {
        return result;
    }

    public ChatResponse result() {
        return result;
    }

    public void setResult(ChatResponse result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public String error() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public boolean cancelRequested() {
        return cancelRequested.get();
    }

    public boolean requestCancel() {
        return cancelRequested.compareAndSet(false, true);
    }

    public CompletableFuture<Void> getDone() {
        return done;
    }

    public CompletableFuture<Void> done() {
        return done;
    }

    public void completeDone() {
        done.complete(null);
    }

    public boolean isTerminal() {
        return status == TurnStatus.COMPLETED
                || status == TurnStatus.FAILED
                || status == TurnStatus.CANCELLED;
    }
}
