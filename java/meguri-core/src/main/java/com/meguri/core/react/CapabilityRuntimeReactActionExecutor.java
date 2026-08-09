package com.meguri.core.react;

import com.meguri.core.agent.ExecutionDomain;
import com.meguri.core.agent.StepDispatcher;
import com.meguri.core.capability.CapabilityResult;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ToolProposal;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Safe adapter to the existing frozen Capability Runtime. The facade remains
 * authoritative for snapshot, policy, schema, approval, cost, audit, and
 * idempotency checks; blocking execution stays on StepDispatcher's I/O lane.
 */
public final class CapabilityRuntimeReactActionExecutor implements ReactActionExecutor {
    private final CapabilityRuntimeFacade capabilities;
    private final CapabilityRuntimeFacade.TurnCapabilities frozenTurn;
    private final StepDispatcher dispatcher;
    private final Clock clock;

    public CapabilityRuntimeReactActionExecutor(
            CapabilityRuntimeFacade capabilities,
            CapabilityRuntimeFacade.TurnCapabilities frozenTurn,
            StepDispatcher dispatcher,
            Clock clock) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.frozenTurn = Objects.requireNonNull(frozenTurn, "frozenTurn");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<RawReactObservation> execute(
            ValidatedReactAction action,
            ReactExecutionContext context) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(context, "context");
        String scopeError = validateScope(context);
        if (scopeError != null) return Mono.just(failure(scopeError, false));

        long remainingMillis = Duration.between(
                clock.instant(), context.deadlineAt()).toMillis();
        if (remainingMillis <= 0) return Mono.just(failure("DEADLINE_EXCEEDED", false));

        ToolProposal proposal = new ToolProposal(
                context.scope().turnId(),
                context.scope().traceId(),
                context.scope().tenantId(),
                context.scope().userId(),
                context.scope().clientId(),
                action.descriptor().id(),
                action.action().arguments(),
                context.scope().scopes(),
                null,
                null,
                null,
                context.scope().networkAllowed(),
                context.remainingCostUnits());

        Mono<RawReactObservation> work = dispatcher.dispatch(
                ExecutionDomain.BLOCKING_IO,
                () -> Mono.fromCallable(() -> map(
                        capabilities.execute(frozenTurn, proposal), action)));
        Mono<RawReactObservation> cancelled = context.cancellation().onCancel()
                .thenReturn(failure("CANCELLED", false));
        return Mono.firstWithSignal(work, cancelled)
                .timeout(Duration.ofMillis(remainingMillis),
                        Mono.just(failure("DEADLINE_EXCEEDED", false)));
    }

    private String validateScope(ReactExecutionContext context) {
        if (context.cancellation().isCancelled()) return "CANCELLED";
        if (!frozenTurn.turnId().equals(context.scope().turnId())) {
            return "TURN_SCOPE_MISMATCH";
        }
        if (!frozenTurn.snapshotId().equals(
                context.scope().capabilitySnapshotVersion())) {
            return "CAPABILITY_SNAPSHOT_MISMATCH";
        }
        return null;
    }

    private static RawReactObservation map(
            CapabilityResult result,
            ValidatedReactAction action) {
        long chargedCost = action.descriptor().cost().estimatedUnits();
        if (result.status() == CapabilityResult.Status.SUCCESS) {
            return new RawReactObservation(
                    RawReactObservation.Status.SUCCESS,
                    result.data(),
                    result.display(),
                    null,
                    ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                    false,
                    false,
                    0,
                    chargedCost);
        }
        return new RawReactObservation(
                RawReactObservation.Status.FAILED,
                Map.of(),
                result.display(),
                result.errorCode() == null ? result.status().name() : result.errorCode(),
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                result.retryable(),
                false,
                0,
                chargedCost);
    }

    private static RawReactObservation failure(String code, boolean retryable) {
        return new RawReactObservation(
                RawReactObservation.Status.FAILED,
                Map.of(),
                "Capability action was not executed",
                code,
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                retryable,
                false,
                0,
                0);
    }
}
