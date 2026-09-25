package com.meguri.core.agent;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic local remote-agent substitute. Jobs use stable sequential IDs,
 * bounded poll progression and schema-derived results.
 */
public final class InMemoryRemoteAgentGateway implements RemoteAgentGateway {
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger activeSubmits = new AtomicInteger();
    private final AtomicInteger maxActiveSubmits = new AtomicInteger();
    private final AtomicInteger totalSubmits = new AtomicInteger();
    private final Duration submitDelay;
    private final int pollsBeforeSuccess;

    public InMemoryRemoteAgentGateway() {
        this(Duration.ZERO, 1);
    }

    public InMemoryRemoteAgentGateway(Duration submitDelay, int pollsBeforeSuccess) {
        if (submitDelay == null || submitDelay.isNegative()) {
            throw new IllegalArgumentException("submitDelay must be non-negative");
        }
        if (pollsBeforeSuccess < 0) throw new IllegalArgumentException("pollsBeforeSuccess must be non-negative");
        this.submitDelay = submitDelay;
        this.pollsBeforeSuccess = pollsBeforeSuccess;
    }

    @Override
    public Mono<RemoteSubmission> submit(AgentTask task, InvokeAgentProposal proposal) {
        return Mono.defer(() -> {
            int active = activeSubmits.incrementAndGet();
            maxActiveSubmits.accumulateAndGet(active, Math::max);
            AtomicBoolean released = new AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) activeSubmits.decrementAndGet();
            };
            Mono<Long> delay = submitDelay.isZero() ? Mono.just(0L) : Mono.delay(submitDelay);
            return delay.map(ignored -> {
                        String remoteTaskId = "local-agent-%06d".formatted(sequence.incrementAndGet());
                        Job job = new Job(task, generatedResult(proposal));
                        jobs.put(remoteTaskId, job);
                        totalSubmits.incrementAndGet();
                        return new RemoteSubmission(remoteTaskId);
                    })
                    .doOnSuccess(ignored -> release.run())
                    .doOnError(ignored -> release.run())
                    .doOnCancel(release);
        });
    }

    @Override
    public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
        return Mono.fromCallable(() -> requireJob(remoteTaskId).poll(pollsBeforeSuccess));
    }

    @Override
    public Mono<Void> cancel(String remoteTaskId) {
        return Mono.fromRunnable(() -> requireJob(remoteTaskId).cancel());
    }

    @Override
    public Mono<AgentResult> result(String remoteTaskId) {
        return Mono.fromCallable(() -> requireJob(remoteTaskId).result());
    }

    public void complete(String remoteTaskId) {
        requireJob(remoteTaskId).complete(null);
    }

    public void complete(String remoteTaskId, AgentResult result) {
        requireJob(remoteTaskId).complete(result);
    }

    public void waitExternally(String remoteTaskId) {
        requireJob(remoteTaskId).waitExternally();
    }

    public void fail(String remoteTaskId) {
        requireJob(remoteTaskId).fail();
    }

    public void timeOut(String remoteTaskId) {
        requireJob(remoteTaskId).timeOut();
    }

    public Optional<JobSnapshot> task(String remoteTaskId) {
        Job job = jobs.get(remoteTaskId);
        return job == null ? Optional.empty() : Optional.of(job.snapshot(remoteTaskId));
    }

    public List<JobSnapshot> tasks() {
        List<JobSnapshot> snapshots = new ArrayList<>();
        jobs.forEach((id, job) -> snapshots.add(job.snapshot(id)));
        snapshots.sort(java.util.Comparator.comparing(JobSnapshot::remoteTaskId));
        return List.copyOf(snapshots);
    }

    public Metrics metrics() {
        return new Metrics(totalSubmits.get(), activeSubmits.get(), maxActiveSubmits.get(), jobs.size());
    }

    private Job requireJob(String remoteTaskId) {
        Job job = jobs.get(remoteTaskId);
        if (job == null) throw new AgentUnavailableException("unknown local remote task: " + remoteTaskId);
        return job;
    }

    private static AgentResult generatedResult(InvokeAgentProposal proposal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        proposal.resultSchema().requiredFields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> payload.put(entry.getKey(), value(entry.getValue(), proposal.taskBrief())));
        return new AgentResult(
                proposal.resultSchema().schemaId(),
                proposal.agentId(),
                payload,
                false,
                null);
    }

    private static Object value(InvokeAgentProposal.ValueType type, String taskBrief) {
        return switch (type) {
            case STRING -> "local-result: " + taskBrief;
            case NUMBER -> 0;
            case BOOLEAN -> false;
            case OBJECT -> Map.of();
            case ARRAY -> List.of();
        };
    }

    public record JobSnapshot(
            String remoteTaskId,
            String localTaskId,
            RemoteAgentStatus status,
            int pollCount,
            AgentResult result) {
    }

    public record Metrics(
            int totalSubmits,
            int activeSubmits,
            int maxActiveSubmits,
            int taskCount) {
    }

    private static final class Job {
        private final AgentTask task;
        private int pollCount;
        private RemoteAgentStatus status = RemoteAgentStatus.QUEUED;
        private AgentResult result;

        private Job(AgentTask task, AgentResult result) {
            this.task = task;
            this.result = result;
        }

        private synchronized RemoteAgentStatus poll(int pollsBeforeSuccess) {
            if (terminal(status) || status == RemoteAgentStatus.WAITING_EXTERNAL) return status;
            pollCount++;
            status = pollCount <= pollsBeforeSuccess
                    ? RemoteAgentStatus.RUNNING
                    : RemoteAgentStatus.SUCCEEDED;
            return status;
        }

        private synchronized void complete(AgentResult replacement) {
            if (terminal(status)) throw new IllegalStateException("local remote task is terminal");
            if (replacement != null) result = replacement;
            status = RemoteAgentStatus.SUCCEEDED;
        }

        private synchronized void waitExternally() {
            if (terminal(status)) throw new IllegalStateException("local remote task is terminal");
            status = RemoteAgentStatus.WAITING_EXTERNAL;
        }

        private synchronized void fail() {
            if (terminal(status)) throw new IllegalStateException("local remote task is terminal");
            status = RemoteAgentStatus.FAILED;
        }

        private synchronized void timeOut() {
            if (terminal(status)) throw new IllegalStateException("local remote task is terminal");
            status = RemoteAgentStatus.TIMED_OUT;
        }

        private synchronized void cancel() {
            if (!terminal(status)) status = RemoteAgentStatus.CANCELLED;
        }

        private synchronized AgentResult result() {
            if (status != RemoteAgentStatus.SUCCEEDED) {
                throw new IllegalStateException("local remote task has no successful result");
            }
            return result;
        }

        private synchronized JobSnapshot snapshot(String remoteTaskId) {
            return new JobSnapshot(remoteTaskId, task.taskId(), status, pollCount, result);
        }

        private static boolean terminal(RemoteAgentStatus status) {
            return status == RemoteAgentStatus.SUCCEEDED
                    || status == RemoteAgentStatus.FAILED
                    || status == RemoteAgentStatus.CANCELLED
                    || status == RemoteAgentStatus.TIMED_OUT;
        }
    }
}
