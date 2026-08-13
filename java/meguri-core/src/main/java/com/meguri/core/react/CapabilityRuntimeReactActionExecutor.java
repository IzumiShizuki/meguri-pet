package com.meguri.core.react;

import com.meguri.core.agent.ExecutionDomain;
import com.meguri.core.agent.StepDispatcher;
import com.meguri.core.capability.CapabilityResult;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.PromptSkillContextContract;
import com.meguri.core.capability.ToolProposal;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                action.action().operationId(),
                action.action().idempotencyKey(),
                action.action().approvalId(),
                context.scope().networkAllowed(),
                context.remainingCostUnits());

        Mono<RawReactObservation> work = dispatcher.dispatch(
                ExecutionDomain.BLOCKING_IO,
                () -> Mono.fromCallable(() -> executeFrozen(action, context, proposal)));
        Mono<RawReactObservation> cancelled = context.cancellation().onCancel()
                .thenReturn(failure("CANCELLED", false));
        return Mono.firstWithSignal(work, cancelled)
                .timeout(Duration.ofMillis(remainingMillis),
                        Mono.just(failure("DEADLINE_EXCEEDED", false)));
    }

    private RawReactObservation executeFrozen(
            ValidatedReactAction action,
            ReactExecutionContext context,
            ToolProposal proposal) {
        if (action.descriptor().kind() == com.meguri.core.capability.CapabilityDescriptor.Kind.PROMPT_SKILL) {
            int tokenLimit = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, context.remainingTokens()));
            PromptSkillContextContract.ContextInput skill = capabilities.promptSkillContext(
                    frozenTurn,
                    proposal,
                    context.scope().turnId() + ":react:" + context.roundIndex(),
                    tokenLimit);
            return new RawReactObservation(
                    RawReactObservation.Status.SUCCESS,
                    Map.of("content_digest", ActionDigest.sha256(
                            Map.of("content", skill.content()))),
                    skill.content(),
                    null,
                    ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                    false,
                    false,
                    skill.tokenEstimate(),
                    action.descriptor().cost().estimatedUnits());
        }
        return map(capabilities.execute(frozenTurn, proposal), action);
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
            ProjectedResult projected = project(result, action);
            return new RawReactObservation(
                    RawReactObservation.Status.SUCCESS,
                    projected.digestData(),
                    projected.summary(),
                    null,
                    projected.trustLabel(),
                    false,
                    false,
                    projected.tokensUsed(),
                    chargedCost);
        }
        return new RawReactObservation(
                RawReactObservation.Status.FAILED,
                Map.of(),
                result.display().isBlank()
                        ? "Capability action failed"
                        : result.display(),
                result.errorCode() == null ? result.status().name() : result.errorCode(),
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                result.retryable(),
                false,
                0,
                chargedCost);
    }

    private static ProjectedResult project(
            CapabilityResult result,
            ValidatedReactAction action) {
        String capabilityId = action.descriptor().id();
        if ("meguri.skill.view".equals(capabilityId)) {
            String content = string(result.data().get("content"));
            Map<String, Object> digestData = new LinkedHashMap<>();
            copyIfPresent(result.data(), digestData, "skill_id");
            copyIfPresent(result.data(), digestData, "path");
            copyIfPresent(result.data(), digestData, "revision");
            digestData.put("content_digest", ActionDigest.sha256(Map.of("content", content)));
            return new ProjectedResult(
                    content,
                    Map.copyOf(digestData),
                    ObservationTrustLabel.UNTRUSTED_EXTERNAL_SKILL,
                    numeric(result.data().get("tokens_used"), tokenEstimate(content)));
        }
        if ("meguri.skill.search".equals(capabilityId)) {
            String summary = skillSearchSummary(result.data().get("items"));
            return new ProjectedResult(
                    summary,
                    Map.of("candidate_digest", ActionDigest.sha256(
                            Map.of("items", result.data().getOrDefault("items", List.of())))),
                    ObservationTrustLabel.UNTRUSTED_EXTERNAL_SKILL,
                    tokenEstimate(summary));
        }
        return new ProjectedResult(
                result.display(), result.data(),
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT, 0);
    }

    private static String skillSearchSummary(Object rawItems) {
        if (!(rawItems instanceof Iterable<?> items)) {
            return "No frozen external Skill candidates matched";
        }
        List<String> summaries = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> value)) continue;
            String id = string(value.get("skill_id"));
            String name = string(value.get("name"));
            String description = string(value.get("description"));
            if (id.isBlank()) continue;
            StringBuilder line = new StringBuilder("- skill_id=").append(id);
            if (!name.isBlank()) line.append("; name=").append(name);
            if (!description.isBlank()) line.append("; description=").append(description);
            summaries.add(line.toString());
        }
        return summaries.isEmpty()
                ? "No frozen external Skill candidates matched"
                : "Frozen external Skill candidates (untrusted metadata):\n"
                        + String.join("\n", summaries);
    }

    private static void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long numeric(Object value, long fallback) {
        return value instanceof Number number
                ? Math.max(0L, number.longValue()) : fallback;
    }

    private static long tokenEstimate(String content) {
        if (content == null || content.isBlank()) return 0L;
        return Math.max(1L, (content.codePointCount(0, content.length()) + 2L) / 3L);
    }

    private record ProjectedResult(
            String summary,
            Map<String, Object> digestData,
            ObservationTrustLabel trustLabel,
            long tokensUsed) {
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
