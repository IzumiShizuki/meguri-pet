package com.meguri.core.capability;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CapabilityExecutor implements AutoCloseable {
    private final CapabilityPolicy policy;
    private final ApprovalService approvals;
    private final ResultNormalizer normalizer;
    private final OperationStore operations;
    private final CapabilityAudit audit;
    private final ExecutorService workers;
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

    public CapabilityExecutor(
            CapabilityPolicy policy,
            ApprovalService approvals,
            ResultNormalizer normalizer,
            OperationStore operations,
            CapabilityAudit audit) {
        this(policy, approvals, normalizer, operations, audit, Executors.newVirtualThreadPerTaskExecutor());
    }

    CapabilityExecutor(
            CapabilityPolicy policy,
            ApprovalService approvals,
            ResultNormalizer normalizer,
            OperationStore operations,
            CapabilityAudit audit,
            ExecutorService workers) {
        this.policy = policy;
        this.approvals = approvals;
        this.normalizer = normalizer;
        this.operations = operations;
        this.audit = audit;
        this.workers = workers;
    }

    public CapabilityResult execute(ExposurePlanner.ExposurePlan plan, ToolProposal proposal) {
        CapabilityRegistry.Grant grant = plan.grant(proposal.capabilityId());
        if (grant == null || !plan.turnId().equals(proposal.turnId())) {
            return rejected(plan, proposal, null, "CAPABILITY_NOT_EXPOSED");
        }
        CapabilityDescriptor descriptor = grant.descriptor();
        try {
            descriptor.inputSchema().validate(proposal.input(), "input");
        } catch (CapabilityDescriptor.SchemaViolation invalid) {
            return rejected(plan, proposal, descriptor, "INPUT_SCHEMA_INVALID");
        }
        CapabilityPolicy.Decision decision = policy.canExecute(descriptor, proposal);
        if (!decision.allowed()) return rejected(plan, proposal, descriptor, decision.code());

        ApprovalService.Decision approval = approvalDecision(plan, proposal, descriptor);
        if (needsApproval(descriptor) && approval != ApprovalService.Decision.ACCEPT) {
            String code = switch (approval) {
                case DECLINE -> "APPROVAL_DECLINED";
                case CANCEL -> "APPROVAL_CANCELLED";
                default -> "APPROVAL_REQUIRED";
            };
            return rejected(plan, proposal, descriptor, code);
        }

        if (descriptor.sideEffect() == CapabilityDescriptor.SideEffect.WRITE) {
            if (proposal.operationId() == null) return rejected(plan, proposal, descriptor, "OPERATION_ID_REQUIRED");
            if (descriptor.idempotency().required() && proposal.idempotencyKey() == null) {
                return rejected(plan, proposal, descriptor, "IDEMPOTENCY_KEY_REQUIRED");
            }
            OperationStore.Claim claim = operations.claim(
                    proposal, descriptor, plan.snapshotId());
            if (!claim.created()) {
                String requestDigest = CapabilityDigest.sha256(proposal.input());
                if (claim.entry().requestDigest() != null
                        && !claim.entry().requestDigest().equals(requestDigest)) {
                    return rejected(
                            plan, proposal, descriptor,
                            "IDEMPOTENCY_PAYLOAD_MISMATCH");
                }
                if (claim.entry().result() != null) {
                    CapabilityResult replay = claim.entry().result();
                    record(plan, proposal, descriptor, approval,
                            "IDEMPOTENT_REPLAY", 0, replay, 0);
                    return replay;
                }
                return rejected(plan, proposal, descriptor, "OPERATION_IN_PROGRESS");
            }
        }

        CapabilityResult result = invoke(plan, proposal, grant, approval);
        if (descriptor.sideEffect() == CapabilityDescriptor.SideEffect.WRITE) {
            operations.complete(proposal.operationId(), result);
        }
        return result;
    }

    private CapabilityResult invoke(
            ExposurePlanner.ExposurePlan plan,
            ToolProposal proposal,
            CapabilityRegistry.Grant grant,
            ApprovalService.Decision approval) {
        CapabilityDescriptor descriptor = grant.descriptor();
        Semaphore bulkhead = bulkheads.computeIfAbsent(descriptor.id() + "@" + descriptor.version(),
                ignored -> new Semaphore(descriptor.concurrency().bulkheadLimit()));
        if (!bulkhead.tryAcquire()) return rejected(plan, proposal, descriptor, "BULKHEAD_REJECTED");
        long started = System.nanoTime();
        try {
            int attempts = retryAttempts(descriptor);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    Map<String, Object> raw = timedInvoke(grant, proposal, attempt, descriptor.timeout());
                    CapabilityResult result = normalizer.normalize(descriptor, raw);
                    record(plan, proposal, descriptor, approval, "COMPLETED", attempt, result,
                            elapsedMillis(started));
                    return result;
                } catch (TimeoutException timeout) {
                    CapabilityResult result = timeoutResult(descriptor);
                    record(plan, proposal, descriptor, approval, "COMPLETED", attempt, result,
                            elapsedMillis(started));
                    return result;
                } catch (CapabilityDescriptor.SchemaViolation invalidOutput) {
                    CapabilityResult result = CapabilityResult.failure("OUTPUT_SCHEMA_INVALID", false);
                    record(plan, proposal, descriptor, approval, "COMPLETED", attempt, result,
                            elapsedMillis(started));
                    return result;
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    CapabilityResult result = CapabilityResult.failure("CANCELLED", false);
                    record(plan, proposal, descriptor, approval, "CANCELLED", attempt, result,
                            elapsedMillis(started));
                    return result;
                } catch (Exception failure) {
                    boolean retry = attempt < attempts;
                    if (retry) sleep(descriptor.retry().backoff());
                    else {
                        CapabilityResult result = CapabilityResult.failure("IMPLEMENTATION_FAILED",
                                descriptor.sideEffect() != CapabilityDescriptor.SideEffect.WRITE);
                        record(plan, proposal, descriptor, approval, "COMPLETED", attempt, result,
                                elapsedMillis(started));
                        return result;
                    }
                }
            }
            throw new IllegalStateException("unreachable");
        } finally {
            bulkhead.release();
        }
    }

    private Map<String, Object> timedInvoke(
            CapabilityRegistry.Grant grant, ToolProposal proposal, int attempt, Duration timeout)
            throws Exception {
        Future<Map<String, Object>> future = workers.submit(() -> {
            try {
                return grant.implementation().invoke(proposal.input(),
                        new CapabilityImplementation.ExecutionContext(
                                proposal.turnId(), proposal.traceId(), proposal.operationId(), attempt));
            } catch (Exception failure) {
                throw new InvocationFailure(failure);
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw timeoutFailure;
        } catch (ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof InvocationFailure invocation) throw (Exception) invocation.getCause();
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private ApprovalService.Decision approvalDecision(
            ExposurePlanner.ExposurePlan plan,
            ToolProposal proposal,
            CapabilityDescriptor descriptor) {
        if (proposal.approvalId() == null) return ApprovalService.Decision.PENDING;
        return approvals.findApproval(proposal.approvalId())
                .filter(value -> value.capabilityId().equals(descriptor.id()))
                .filter(value -> java.util.Objects.equals(
                        value.capabilityVersion(), descriptor.version()))
                .filter(value -> java.util.Objects.equals(value.operationId(), proposal.operationId()))
                .filter(value -> value.snapshotId().equals(plan.snapshotId()))
                .filter(value -> value.turnId().equals(proposal.turnId()))
                .filter(value -> value.traceId().equals(proposal.traceId()))
                .filter(value -> value.tenantId().equals(proposal.tenantId()))
                .filter(value -> value.userId().equals(proposal.userId()))
                .filter(value -> value.clientId().equals(proposal.clientId()))
                .filter(value -> java.util.Objects.equals(
                        value.idempotencyKey(), proposal.idempotencyKey()))
                .filter(value -> value.requestDigest().equals(
                        CapabilityDigest.sha256(proposal.input())))
                .map(ApprovalService.Approval::decision)
                .orElse(ApprovalService.Decision.PENDING);
    }

    private static boolean needsApproval(CapabilityDescriptor descriptor) {
        return descriptor.approval() != CapabilityDescriptor.ApprovalRequirement.NONE
                || descriptor.kind() == CapabilityDescriptor.Kind.WRITE_TOOL;
    }

    private static int retryAttempts(CapabilityDescriptor descriptor) {
        if (descriptor.sideEffect() == CapabilityDescriptor.SideEffect.WRITE
                && !descriptor.idempotency().required()) return 1;
        return descriptor.retry().maxAttempts();
    }

    private static CapabilityResult timeoutResult(CapabilityDescriptor descriptor) {
        if (descriptor.sideEffect() == CapabilityDescriptor.SideEffect.WRITE) {
            return new CapabilityResult(CapabilityResult.Status.UNKNOWN_OUTCOME, "UNKNOWN_OUTCOME",
                    Map.of(), "", null, java.util.List.of("VERIFY_BEFORE_RETRY"), false);
        }
        return CapabilityResult.failure("TIMEOUT", true);
    }

    private CapabilityResult rejected(
            ExposurePlanner.ExposurePlan plan,
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String code) {
        CapabilityResult result = CapabilityResult.failure(code, false);
        ApprovalService.Decision approval = descriptor == null
                ? ApprovalService.Decision.PENDING
                : approvalDecision(plan, proposal, descriptor);
        record(plan, proposal, descriptor, approval, "REJECTED", 0, result, 0);
        return result;
    }

    private void record(
            ExposurePlanner.ExposurePlan plan,
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            ApprovalService.Decision approval,
            String phase,
            int attempt,
            CapabilityResult result,
            long durationMs) {
        audit.record(new CapabilityAudit.Event(UUID.randomUUID().toString(), Instant.now(),
                proposal.turnId(), proposal.traceId(), plan.snapshotId(), proposal.tenantId(), proposal.userId(),
                proposal.clientId(), proposal.capabilityId(), descriptor == null ? null : descriptor.version(),
                proposal.operationId(), proposal.idempotencyKey(),
                CapabilityDigest.sha256(proposal.input()),
                CapabilityDigest.result(result),
                proposal.approvalId(), approval, phase, attempt,
                result.status().name(), result.errorCode(), durationMs,
                descriptor != null && descriptor.resultTrust() == CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL,
                result.retryable()));
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) return;
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    private static final class InvocationFailure extends RuntimeException {
        private InvocationFailure(Throwable cause) { super(cause); }
    }
}
