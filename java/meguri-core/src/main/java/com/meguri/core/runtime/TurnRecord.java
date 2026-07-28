package com.meguri.core.runtime;

import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.HarnessManifest;
import com.meguri.core.harness.persona.PersonaRuntime;
import com.meguri.core.harness.capability.CapabilityRegistry;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.agent.CancellationToken;

import java.time.Duration;
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
    private final Instant deadlineAt;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final CancellationToken agentCancellation = new CancellationToken();
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private volatile TurnStatus status = TurnStatus.ACCEPTED;
    private volatile TurnStage stage = TurnStage.CREATED;
    private volatile HarnessManifest manifest;
    private volatile PersonaRuntime.PersonaSnapshot personaSnapshot;
    private volatile CapabilityRegistry.Snapshot capabilitySnapshot;
    private volatile CapabilityRuntimeFacade.TurnCapabilities runtimeCapabilities;
    private volatile ChatResponse result;
    private volatile String error;

    public TurnRecord(String turnId, String traceId, TurnRequest request) {
        this(turnId, traceId, request, Instant.now().plusSeconds(90));
    }

    public TurnRecord(String turnId, String traceId, TurnRequest request, Instant deadlineAt) {
        this(turnId, traceId, request, Instant.now(), deadlineAt);
    }

    public TurnRecord(String turnId, String traceId, TurnRequest request,
                      Instant acceptedAt, Instant deadlineAt) {
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.request = Objects.requireNonNull(request, "request");
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (!deadlineAt.isAfter(acceptedAt)) throw new IllegalArgumentException("deadlineAt must be after acceptance");
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

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Duration remaining() {
        Duration remaining = Duration.between(Instant.now(), deadlineAt);
        return remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining;
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

    public TurnStage getStage() {
        return stage;
    }

    public synchronized boolean transitionTo(TurnStage next) {
        Objects.requireNonNull(next, "next");
        if (stage.terminal()) return stage == next;
        if (!next.terminal() && next.ordinal() < stage.ordinal()) {
            throw new IllegalStateException("turn stage cannot move backwards from " + stage + " to " + next);
        }
        stage = next;
        return true;
    }

    public HarnessManifest getManifest() {
        return manifest;
    }

    public synchronized void freezeManifest(HarnessManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (this.manifest != null && !this.manifest.equals(manifest)) {
            throw new IllegalStateException("turn manifest is already frozen");
        }
        this.manifest = manifest;
    }

    public PersonaRuntime.PersonaSnapshot getPersonaSnapshot() {
        return personaSnapshot;
    }

    public synchronized void freezePersonaSnapshot(PersonaRuntime.PersonaSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (personaSnapshot != null && !personaSnapshot.equals(snapshot)) {
            throw new IllegalStateException("persona snapshot is already frozen");
        }
        personaSnapshot = snapshot;
    }

    public CapabilityRegistry.Snapshot getCapabilitySnapshot() {
        return capabilitySnapshot;
    }

    public synchronized void freezeCapabilitySnapshot(CapabilityRegistry.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (capabilitySnapshot != null && !capabilitySnapshot.equals(snapshot)) {
            throw new IllegalStateException("capability snapshot is already frozen");
        }
        capabilitySnapshot = snapshot;
    }

    public CapabilityRuntimeFacade.TurnCapabilities getRuntimeCapabilities() {
        return runtimeCapabilities;
    }

    public synchronized void freezeRuntimeCapabilities(
            CapabilityRuntimeFacade.TurnCapabilities capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (runtimeCapabilities != null && !runtimeCapabilities.equals(capabilities)) {
            throw new IllegalStateException("runtime capabilities are already frozen");
        }
        runtimeCapabilities = capabilities;
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

    /** Atomically wins the terminal race only when no cancellation is pending. */
    public synchronized boolean tryComplete(ChatResponse completedResult) {
        Objects.requireNonNull(completedResult, "completedResult");
        if (stage.terminal() || cancelRequested.get()) return false;
        result = completedResult;
        status = TurnStatus.COMPLETED;
        stage = TurnStage.COMPLETED;
        return true;
    }

    public synchronized boolean tryCancel() {
        cancelRequested.set(true);
        agentCancellation.cancel();
        if (stage.terminal()) return false;
        status = TurnStatus.CANCELLED;
        stage = TurnStage.CANCELLED;
        return true;
    }

    public synchronized boolean tryFail(String failure) {
        if (stage.terminal() || cancelRequested.get()) return false;
        status = TurnStatus.FAILED;
        stage = TurnStage.FAILED;
        error = failure;
        return true;
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
        boolean changed = cancelRequested.compareAndSet(false, true);
        agentCancellation.cancel();
        return changed;
    }

    public CancellationToken agentCancellation() {
        return agentCancellation;
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

    /** Rehydrates durable lifecycle fields without replaying business effects. */
    public synchronized void restore(TurnStatus status, TurnStage stage,
                                     ChatResponse result, String error) {
        restore(status, stage, null, result, error);
    }

    /** Rehydrates durable lifecycle fields, including the frozen manifest. */
    public synchronized void restore(TurnStatus status, TurnStage stage,
                                     HarnessManifest manifest, ChatResponse result, String error) {
        this.status = Objects.requireNonNull(status, "status");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.manifest = manifest;
        this.result = result;
        this.error = error;
        if (stage.terminal()) completeDone();
    }

    public boolean isTerminal() {
        return stage.terminal();
    }
}
