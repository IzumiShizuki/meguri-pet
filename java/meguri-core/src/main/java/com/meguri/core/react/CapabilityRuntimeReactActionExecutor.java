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
import java.util.Set;

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
        if ("web_fetch".equals(capabilityId)) {
            StringBuilder summary = new StringBuilder("External web document (untrusted data): ");
            appendBounded(summary, "URL: " + string(result.data().get("url"))
                    + "; title: " + string(result.data().get("title")) + "\nLinks: ");
            // Preserve selectable links before bounded body text, without granting page instructions authority.
            appendMcpValue(summary, result.data().get("links"), 0);
            appendBounded(summary, "\nDocument text: " + string(result.data().get("text")));
            return new ProjectedResult(summary.toString(),
                    Map.of("result_digest", ActionDigest.sha256(result.data())),
                    ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT, tokenEstimate(summary.toString()));
        }
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
        if (capabilityId.startsWith("shizuki.")) {
            String summary = string(result.data().get("summary"));
            if (summary.isBlank()) summary = "Shizuki local operation completed";
            return new ProjectedResult(
                    summary,
                    result.data(),
                    ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                    tokenEstimate(summary));
        }
        if (capabilityId.startsWith("mcp.") || capabilityId.equals("meguri.skill.discover")
                || capabilityId.equals("meguri.skill.inspect")) {
            // The normalizer already redacted sensitive keys. Only bounded result text
            // reaches the planner; never promote an MCP observation to instructions.
            String summary = mcpSummary(result.data());
            return new ProjectedResult(summary,
                    Map.of("result_digest", ActionDigest.sha256(result.data())),
                    ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT, tokenEstimate(summary));
        }
        return new ProjectedResult(
                result.display(), result.data(),
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT, 0);
    }

    private static String mcpSummary(Map<String, Object> data) {
        StringBuilder summary = new StringBuilder("External result (untrusted data): ");
        appendMcpValue(summary, data, 0);
        return summary.toString();
    }

    private static void appendMcpValue(StringBuilder out, Object value, int depth) {
        if (out.length() >= 4096) return;
        if (depth > 6) { appendBounded(out, "[nested data omitted]"); return; }
        if (value instanceof Map<?, ?> map) {
            if (Set.of("image", "audio").contains(String.valueOf(map.get("type")))) {
                appendBounded(out, "[binary content omitted]");
                return;
            }
            int count = 0;
            for (var entry : map.entrySet()) {
                if (out.length() >= 4096) break;
                if (++count > 32) { appendBounded(out, "[additional fields omitted]"); break; }
                if (String.valueOf(entry.getKey()).equals("blob")) continue;
                appendBounded(out, String.valueOf(entry.getKey()) + ": ");
                appendMcpValue(out, entry.getValue(), depth + 1);
                appendBounded(out, "; ");
            }
        } else if (value instanceof Iterable<?> items) {
            int count = 0;
            for (Object item : items) {
                if (out.length() >= 4096) break;
                if (++count > 16) { appendBounded(out, "[additional items omitted]"); break; }
                appendMcpValue(out, item, depth + 1);
                appendBounded(out, " | ");
            }
        } else appendBounded(out, String.valueOf(value));
    }

    private static void appendBounded(StringBuilder out, String text) {
        int remaining = 4096 - out.length();
        if (remaining <= 0) return;
        if (text.length() <= remaining) { out.append(text); return; }
        String marker = " [truncated]";
        if (remaining < marker.length()) { out.append(marker, 0, remaining); return; }
        int end = remaining - marker.length();
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        out.append(text, 0, end).append(marker);
    }

    private static String skillSearchSummary(Object rawItems) {
        if (!(rawItems instanceof Iterable<?> items)) {
            return "No installed frozen Skill candidates matched. Local inventory only; public community was not searched.";
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
                ? "No installed frozen Skill candidates matched. Local inventory only; public community was not searched."
                : "Installed frozen Skill candidates (local inventory only; untrusted metadata):\n"
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
