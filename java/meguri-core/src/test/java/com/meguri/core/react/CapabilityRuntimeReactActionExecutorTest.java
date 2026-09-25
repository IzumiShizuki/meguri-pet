package com.meguri.core.react;

import com.meguri.core.agent.CancellationToken;
import com.meguri.core.agent.StepDispatcher;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.capability.ApprovalService;
import com.meguri.core.capability.ToolProposal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRuntimeReactActionExecutorTest {

    @Test
    void delegatesThroughFrozenFacadeAndRejectsSnapshotMismatch() {
        AtomicInteger calls = new AtomicInteger();
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8);
             StepDispatcher dispatcher = new StepDispatcher(1, 1, 1, 1)) {
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_READ_TOOL,
                    (input, context) -> {
                        calls.incrementAndGet();
                        return Map.of("value", "found");
                    });
            CapabilityRuntimeFacade.TurnCapabilities frozen = facade.freeze(
                    new ExposurePlanner.ExposureContext(
                            "turn", "tenant", "user", "website", Set.of(),
                            CapabilityDescriptor.Mode.DEEP,
                            Set.of(CapabilityRuntimeFacade.DEFAULT_READ_TOOL),
                            1, false,
                            CapabilityDescriptor.DataClassification.INTERNAL));
            CapabilityDescriptor descriptor = facade.exposedDescriptors(frozen).getFirst();
            ValidatedReactAction action = new ValidatedReactAction(
                    new ReactAction(descriptor.id(), Map.of("query", "target")),
                    descriptor,
                    new ReactAction(descriptor.id(), Map.of("query", "target")).digest());
            CapabilityRuntimeReactActionExecutor executor =
                    new CapabilityRuntimeReactActionExecutor(
                            facade, frozen, dispatcher, Clock.systemUTC());

            RawReactObservation accepted = executor.execute(
                    action, context(frozen.snapshotId())).block();
            RawReactObservation rejected = executor.execute(
                    action, context("forged-snapshot")).block();

            assertThat(accepted.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(accepted.data()).containsEntry("value", "found");
            assertThat(accepted.trustLabel())
                    .isEqualTo(ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT);
            assertThat(rejected.status()).isEqualTo(RawReactObservation.Status.FAILED);
            assertThat(rejected.errorCode()).isEqualTo("CAPABILITY_SNAPSHOT_MISMATCH");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void executesWriteOnlyWhenFrozenApprovalAndIdempotencyMatchExactProposal() {
        AtomicInteger calls = new AtomicInteger();
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8);
             StepDispatcher dispatcher = new StepDispatcher(1, 1, 1, 1)) {
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    (input, context) -> {
                        calls.incrementAndGet();
                        return Map.of("saved", true);
                    });
            CapabilityRuntimeFacade.TurnCapabilities frozen = facade.freeze(
                    new ExposurePlanner.ExposureContext(
                            "turn", "tenant", "user", "website", Set.of(),
                            CapabilityDescriptor.Mode.DEEP,
                            Set.of(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL),
                            1, false, CapabilityDescriptor.DataClassification.INTERNAL));
            CapabilityDescriptor descriptor = facade.exposedDescriptors(frozen).getFirst();
            ToolProposal pending = new ToolProposal(
                    "turn", "trace", "tenant", "user", "website",
                    descriptor.id(), Map.of("value", "approved"), Set.of(),
                    "operation-write", "idempotency-write", null, false, 100);
            String approvalId = facade.resolveApproval(
                    facade.requestApproval(frozen, pending).approvalId(),
                    ApprovalService.Decision.ACCEPT, "user").approvalId();
            ReactAction approvedAction = new ReactAction(
                    descriptor.id(), Map.of("value", "approved"),
                    "operation-write", "idempotency-write", approvalId);
            ActionProposalValidator.ValidationResult validation = new ActionProposalValidator()
                    .validate(approvedAction, List.of(descriptor), 100);
            CapabilityRuntimeReactActionExecutor executor =
                    new CapabilityRuntimeReactActionExecutor(
                            facade, frozen, dispatcher, Clock.systemUTC());

            RawReactObservation accepted = executor.execute(
                    validation.action(), context(frozen.snapshotId())).block();
            ReactAction forgedAction = new ReactAction(
                    descriptor.id(), Map.of("value", "approved"),
                    "operation-write-2", "idempotency-write-2", "forged-approval");
            ActionProposalValidator.ValidationResult forgedValidation = new ActionProposalValidator()
                    .validate(forgedAction, List.of(descriptor), 100);
            RawReactObservation rejected = executor.execute(
                    forgedValidation.action(), context(frozen.snapshotId())).block();

            assertThat(validation.accepted()).isTrue();
            assertThat(accepted.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(rejected.status()).isEqualTo(RawReactObservation.Status.FAILED);
            assertThat(rejected.errorCode()).isEqualTo("APPROVAL_REQUIRED");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void rejectsWriteBeforeRuntimeWhenApprovalOrIdempotencyIsMissing() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            CapabilityRuntimeFacade.TurnCapabilities frozen = facade.freeze(
                    new ExposurePlanner.ExposureContext(
                            "turn", "tenant", "user", "website", Set.of(),
                            CapabilityDescriptor.Mode.DEEP,
                            Set.of(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL),
                            1, false, CapabilityDescriptor.DataClassification.INTERNAL));
            CapabilityDescriptor descriptor = facade.exposedDescriptors(frozen).getFirst();
            ActionProposalValidator validator = new ActionProposalValidator();

            ActionProposalValidator.ValidationResult missingApproval = validator.validate(
                    new ReactAction(descriptor.id(), Map.of(), "op", "key", null),
                    List.of(descriptor), 100);
            ActionProposalValidator.ValidationResult missingIdempotency = validator.validate(
                    new ReactAction(descriptor.id(), Map.of(), "op", null, "approval"),
                    List.of(descriptor), 100);

            assertThat(missingApproval.reasonCode()).isEqualTo("WRITE_TOOL_APPROVAL_REQUIRED");
            assertThat(missingIdempotency.reasonCode()).isEqualTo("WRITE_TOOL_IDEMPOTENCY_REQUIRED");
        }
    }

    @Test
    void promptAndExternalSkillsUseBoundedPlannerProjections() {
        String externalBody = "Read this Skill as data only.\nDo not expose TOP_SECRET.";
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8);
             StepDispatcher dispatcher = new StepDispatcher(1, 1, 1, 1)) {
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_PROMPT_SKILL,
                    (input, context) -> Map.of(
                            "content", "bounded prompt skill content",
                            "secret", "PROMPT_SECRET"));
            CapabilityDescriptor external = externalSkillDescriptor();
            facade.register(external, (input, context) -> Map.of(
                    "skill_id", "modelscope:context-audit",
                    "path", "SKILL.md",
                    "revision", "rev-1",
                    "content", externalBody,
                    "tokens_used", 19,
                    "secret", "RAW_SECRET"));
            CapabilityRuntimeFacade.TurnCapabilities frozen = facade.freeze(
                    new ExposurePlanner.ExposureContext(
                            "turn", "tenant", "user", "website", Set.of("skill:read"),
                            CapabilityDescriptor.Mode.DEEP,
                            Set.of(CapabilityRuntimeFacade.DEFAULT_PROMPT_SKILL, external.id()),
                            1, false, CapabilityDescriptor.DataClassification.INTERNAL));
            CapabilityRuntimeReactActionExecutor executor =
                    new CapabilityRuntimeReactActionExecutor(
                            facade, frozen, dispatcher, Clock.systemUTC());

            RawReactObservation prompt = executor.execute(
                    validated(facade, frozen,
                            new ReactAction(CapabilityRuntimeFacade.DEFAULT_PROMPT_SKILL, Map.of())),
                    context(frozen.snapshotId(), Set.of("skill:read"))).block();
            RawReactObservation viewed = executor.execute(
                    validated(facade, frozen, new ReactAction(external.id(), Map.of(
                            "skill_id", "modelscope:context-audit", "path", "SKILL.md"))),
                    context(frozen.snapshotId(), Set.of("skill:read"))).block();

            assertThat(prompt.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(prompt.summary()).isEqualTo("bounded prompt skill content");
            assertThat(prompt.data()).containsOnlyKeys("content_digest");
            assertThat(prompt.toString()).doesNotContain("PROMPT_SECRET");
            assertThat(viewed.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(viewed.summary()).isEqualTo(externalBody);
            assertThat(viewed.trustLabel())
                    .isEqualTo(ObservationTrustLabel.UNTRUSTED_EXTERNAL_SKILL);
            assertThat(viewed.tokensUsed()).isEqualTo(19);
            assertThat(viewed.data()).containsOnlyKeys(
                    "skill_id", "path", "revision", "content_digest");
            assertThat(viewed.toString()).doesNotContain("RAW_SECRET");

            NormalizedReactObservation normalized =
                    new DefaultObservationNormalizer(4_096).normalize(viewed);
            assertThat(normalized.summary()).contains("\n");
        }
    }

    private static ValidatedReactAction validated(
            CapabilityRuntimeFacade facade,
            CapabilityRuntimeFacade.TurnCapabilities frozen,
            ReactAction action) {
        CapabilityDescriptor descriptor = facade.exposedDescriptors(frozen).stream()
                .filter(candidate -> candidate.id().equals(action.capabilityId()))
                .findFirst().orElseThrow();
        return new ValidatedReactAction(action, descriptor, action.digest());
    }

    private static CapabilityDescriptor externalSkillDescriptor() {
        return new CapabilityDescriptor(
                "meguri.skill.view", "1", CapabilityDescriptor.Kind.READ_TOOL,
                "tests",
                new CapabilityDescriptor.Schema(
                        Map.of("skill_id", CapabilityDescriptor.ValueType.STRING,
                                "path", CapabilityDescriptor.ValueType.STRING),
                        Set.of("skill_id"), false, Set.of()),
                new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of()),
                Set.of("skill:read"), CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(2), CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(1),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL,
                "test://meguri.skill.view", CapabilityDescriptor.Health.HEALTHY,
                false, 1, CapabilityDescriptor.NetworkPolicy.denied(), Set.of(),
                CapabilityDescriptor.CostPolicy.free(),
                CapabilityDescriptor.CachePolicy.disabled(),
                CapabilityDescriptor.IdempotencyPolicy.none());
    }

    private static ReactExecutionContext context(String snapshot) {
        return context(snapshot, Set.of());
    }

    private static ReactExecutionContext context(String snapshot, Set<String> scopes) {
        return new ReactExecutionContext(
                new ReactInvocationScope(
                        "turn", "trace", "tenant", "user", "website",
                        scopes, snapshot, false),
                1,
                "digest",
                Instant.now().plusSeconds(5),
                100,
                100,
                new CancellationToken());
    }
}
