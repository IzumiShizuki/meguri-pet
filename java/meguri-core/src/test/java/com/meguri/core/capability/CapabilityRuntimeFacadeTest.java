package com.meguri.core.capability;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRuntimeFacadeTest {

    @Test
    void registersAllDefaultKindsAndSupportsDefaultCallbacks() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_READ_TOOL,
                    (input, context) -> Map.of("callback", "read"));
            CapabilityRuntimeFacade.TurnCapabilities turn = facade.freeze(context(
                    "turn-defaults", Set.of()));

            assertThat(turn.exposed()).extracting(CapabilityRuntimeFacade.CapabilityRef::id)
                    .contains(
                            CapabilityRuntimeFacade.DEFAULT_PROMPT_SKILL,
                            CapabilityRuntimeFacade.DEFAULT_RESOURCE,
                            CapabilityRuntimeFacade.DEFAULT_READ_TOOL,
                            CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                            CapabilityRuntimeFacade.DEFAULT_REMOTE_AGENT);
            assertThat(facade.exposedDescriptors(turn))
                    .extracting(CapabilityDescriptor::kind)
                    .contains(
                            CapabilityDescriptor.Kind.PROMPT_SKILL,
                            CapabilityDescriptor.Kind.RESOURCE,
                            CapabilityDescriptor.Kind.READ_TOOL,
                            CapabilityDescriptor.Kind.WRITE_TOOL,
                            CapabilityDescriptor.Kind.REMOTE_AGENT);
            CapabilityResult result = facade.execute(turn, proposal(
                    "turn-defaults", CapabilityRuntimeFacade.DEFAULT_READ_TOOL,
                    null, null, null));
            assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCESS);
            assertThat(result.data()).containsEntry("callback", "read");
        }
    }

    @Test
    void registerDisableAndEnableOnlyAffectSubsequentTurns() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            CapabilityDescriptor descriptor = readDescriptor("lifecycle.read");
            CapabilityRuntimeFacade.TurnCapabilities beforeRegistration =
                    facade.freeze(context("turn-before", Set.of(descriptor.id())));

            facade.register(descriptor, (input, context) -> Map.of());
            CapabilityRuntimeFacade.TurnCapabilities enabled =
                    facade.freeze(context("turn-enabled", Set.of(descriptor.id())));
            facade.disable(descriptor.id());
            CapabilityRuntimeFacade.TurnCapabilities disabled =
                    facade.freeze(context("turn-disabled", Set.of(descriptor.id())));
            facade.enable(descriptor.id(), descriptor.version());
            CapabilityRuntimeFacade.TurnCapabilities reenabled =
                    facade.freeze(context("turn-reenabled", Set.of(descriptor.id())));

            assertThat(beforeRegistration.exposes(descriptor.id())).isFalse();
            assertThat(enabled.exposes(descriptor.id())).isTrue();
            assertThat(disabled.exposes(descriptor.id())).isFalse();
            assertThat(reenabled.exposes(descriptor.id())).isTrue();
            assertThat(enabled.exposes(descriptor.id())).isTrue();
        }
    }

    @Test
    void oldTurnContinuesAfterDrainWhileNewTurnNoLongerExposesCapability() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            AtomicInteger calls = new AtomicInteger();
            CapabilityDescriptor descriptor = readDescriptor("dynamic.read");
            facade.register(descriptor, (input, context) -> {
                calls.incrementAndGet();
                return Map.of("value", "old-turn");
            });
            CapabilityRuntimeFacade.TurnCapabilities oldTurn =
                    facade.freeze(context("turn-old", Set.of(descriptor.id())));

            facade.drain(descriptor.id());
            CapabilityRuntimeFacade.TurnCapabilities newTurn =
                    facade.freeze(context("turn-new", Set.of(descriptor.id())));

            assertThat(facade.catalogEntries())
                    .filteredOn(entry -> entry.descriptor().id().equals(descriptor.id()))
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.draining()).isTrue();
                        assertThat(entry.retainedByFrozenTurn()).isTrue();
                    });
            assertThat(oldTurn.exposes(descriptor.id())).isTrue();
            assertThat(newTurn.exposes(descriptor.id())).isFalse();
            assertThat(facade.execute(oldTurn, proposal(
                    "turn-old", descriptor.id(), null, null, null)).data())
                    .containsEntry("value", "old-turn");
            assertThat(facade.execute(newTurn, proposal(
                    "turn-new", descriptor.id(), null, null, null)).errorCode())
                    .isEqualTo("CAPABILITY_NOT_EXPOSED");
            assertThat(calls).hasValue(1);
            facade.release(oldTurn);
            assertThat(facade.catalogEntries())
                    .noneMatch(entry -> entry.descriptor().id().equals(descriptor.id()));
        }
    }

    @Test
    void forgedTurnTokenAndUnauthorizedProposalAreRejectedWithoutInvocation() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            AtomicInteger calls = new AtomicInteger();
            CapabilityDescriptor descriptor = readDescriptor("guarded.read");
            facade.register(descriptor, (input, context) -> {
                calls.incrementAndGet();
                return Map.of();
            });
            CapabilityRuntimeFacade.TurnCapabilities turn =
                    facade.freeze(context("turn-guarded", Set.of(descriptor.id())));
            CapabilityRuntimeFacade.TurnCapabilities forged =
                    new CapabilityRuntimeFacade.TurnCapabilities(
                            turn.freezeId(), turn.turnId(), turn.snapshotId(), turn.frozenAt(), List.of());

            CapabilityResult forgedResult = facade.execute(
                    forged, proposal("turn-guarded", descriptor.id(), null, null, null));
            CapabilityResult unauthorized = facade.execute(
                    turn, proposal("turn-guarded", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                            "operation-x", "key-x", null));

            assertThat(forgedResult.errorCode()).isEqualTo("CAPABILITY_NOT_EXPOSED");
            assertThat(unauthorized.errorCode()).isEqualTo("CAPABILITY_NOT_EXPOSED");
            assertThat(calls).hasValue(0);
            assertThat(facade.auditEvents()).hasSize(2);
        }
    }

    @Test
    void writeApprovalIsBoundToCapabilityAndOperation() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            AtomicInteger calls = new AtomicInteger();
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL, (input, context) -> {
                calls.incrementAndGet();
                return Map.of("saved", true);
            });
            CapabilityRuntimeFacade.TurnCapabilities turn = facade.freeze(context(
                    "turn-write", Set.of(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL)));
            ToolProposal operationA = proposal(
                    "turn-write", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    "operation-a", "key-a", null);
            ApprovalService.Approval requested = facade.requestApproval(turn, operationA);
            String acceptedId = facade.resolveApproval(
                    requested.approvalId(), ApprovalService.Decision.ACCEPT, "user").approvalId();

            assertThat(facade.auditEvents())
                    .extracting(CapabilityAudit.Event::phase)
                    .containsExactly("APPROVAL_REQUESTED", "APPROVAL_RESOLVED");
            assertThat(facade.auditEvents()).allSatisfy(event -> {
                assertThat(event.snapshotId()).isEqualTo(turn.snapshotId());
                assertThat(event.requestDigest()).matches("[0-9a-f]{64}");
                assertThat(event.resultDigest()).matches("[0-9a-f]{64}");
                assertThat(event.approvalId()).isEqualTo(acceptedId);
            });

            CapabilityResult wrongOperation = facade.execute(turn, proposal(
                    "turn-write", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    "operation-b", "key-b", acceptedId));
            CapabilityResult accepted = facade.execute(turn, proposal(
                    "turn-write", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    "operation-a", "key-a", acceptedId));

            assertThat(wrongOperation.errorCode()).isEqualTo("APPROVAL_REQUIRED");
            assertThat(accepted.status()).isEqualTo(CapabilityResult.Status.SUCCESS);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void writeApprovalAndIdempotencyAreBoundToTheExactFrozenRequest() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            AtomicInteger calls = new AtomicInteger();
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL, (input, context) -> {
                calls.incrementAndGet();
                return Map.of("saved", true);
            });
            CapabilityRuntimeFacade.TurnCapabilities firstTurn = facade.freeze(context(
                    "turn-write-bound", Set.of(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL)));
            ToolProposal original = proposal(
                    "turn-write-bound", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    Map.of("value", "original"), "operation-bound", "key-bound", null);
            String originalApproval = facade.resolveApproval(
                    facade.requestApproval(firstTurn, original).approvalId(),
                    ApprovalService.Decision.ACCEPT,
                    "user").approvalId();

            ToolProposal changedInput = proposal(
                    "turn-write-bound", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    Map.of("value", "changed"), "operation-bound", "key-bound", originalApproval);
            CapabilityResult stolenApproval = facade.execute(firstTurn, changedInput);

            ToolProposal approvedOriginal = proposal(
                    "turn-write-bound", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    Map.of("value", "original"), "operation-bound", "key-bound", originalApproval);
            CapabilityResult first = facade.execute(firstTurn, approvedOriginal);

            ToolProposal changedForApproval = proposal(
                    "turn-write-bound", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    Map.of("value", "changed"), "operation-bound", "key-bound", null);
            String changedApproval = facade.resolveApproval(
                    facade.requestApproval(firstTurn, changedForApproval).approvalId(),
                    ApprovalService.Decision.ACCEPT,
                    "user").approvalId();
            CapabilityResult conflictingReplay = facade.execute(firstTurn, proposal(
                    "turn-write-bound", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    Map.of("value", "changed"), "operation-bound", "key-bound", changedApproval));

            CapabilityRuntimeFacade.TurnCapabilities secondTurn = facade.freeze(context(
                    "turn-write-other", Set.of(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL)));
            CapabilityResult otherTurn = facade.execute(secondTurn, proposal(
                    "turn-write-other", CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL,
                    Map.of("value", "original"), "operation-bound", "key-bound", originalApproval));

            assertThat(stolenApproval.errorCode()).isEqualTo("APPROVAL_REQUIRED");
            assertThat(first.status()).isEqualTo(CapabilityResult.Status.SUCCESS);
            assertThat(conflictingReplay.errorCode())
                    .isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
            assertThat(otherTurn.errorCode()).isEqualTo("APPROVAL_REQUIRED");
            assertThat(calls).hasValue(1);
            assertThat(facade.auditEvents())
                    .filteredOn(event -> event.phase().equals("REJECTED"))
                    .allSatisfy(event -> {
                        assertThat(event.requestDigest()).matches("[0-9a-f]{64}");
                        assertThat(event.resultDigest()).matches("[0-9a-f]{64}");
                    });
        }
    }

    @Test
    void delegatingRegistrationReceivesDescriptorAndReleaseInvalidatesTurn() {
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8)) {
            List<String> delegated = new ArrayList<>();
            CapabilityDescriptor descriptor = readDescriptor("delegated.read");
            facade.registerDelegating(descriptor, (registered, input, context) -> {
                delegated.add(registered.id() + "@" + registered.version());
                return Map.of();
            });
            CapabilityRuntimeFacade.TurnCapabilities turn =
                    facade.freeze(context("turn-delegated", Set.of(descriptor.id())));

            assertThat(facade.execute(turn, proposal(
                    "turn-delegated", descriptor.id(), null, null, null)).status())
                    .isEqualTo(CapabilityResult.Status.SUCCESS);
            facade.release(turn);
            assertThat(facade.execute(turn, proposal(
                    "turn-delegated", descriptor.id(), null, null, null)).errorCode())
                    .isEqualTo("CAPABILITY_NOT_EXPOSED");
            assertThat(delegated).containsExactly("delegated.read@1");
            assertThat(facade.retainedTurnCount()).isZero();
        }
    }

    private static ExposurePlanner.ExposureContext context(String turnId, Set<String> intent) {
        return new ExposurePlanner.ExposureContext(
                turnId,
                "tenant",
                "user",
                "client",
                Set.of(),
                CapabilityDescriptor.Mode.BALANCED,
                intent,
                1,
                true,
                CapabilityDescriptor.DataClassification.RESTRICTED);
    }

    private static ToolProposal proposal(
            String turnId,
            String capabilityId,
            String operationId,
            String idempotencyKey,
            String approvalId) {
        return proposal(
                turnId, capabilityId, Map.of(), operationId,
                idempotencyKey, approvalId);
    }

    private static ToolProposal proposal(
            String turnId,
            String capabilityId,
            Map<String, Object> input,
            String operationId,
            String idempotencyKey,
            String approvalId) {
        return new ToolProposal(
                turnId,
                "trace-" + turnId,
                "tenant",
                "user",
                "client",
                capabilityId,
                input,
                Set.of(),
                operationId,
                idempotencyKey,
                approvalId,
                true,
                1000);
    }

    private static CapabilityDescriptor readDescriptor(String id) {
        return new CapabilityDescriptor(
                id,
                "1",
                CapabilityDescriptor.Kind.READ_TOOL,
                "tests",
                CapabilityDescriptor.Schema.empty(),
                new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of()),
                Set.of(),
                CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(2),
                CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(1),
                Set.of(CapabilityDescriptor.Mode.FAST, CapabilityDescriptor.Mode.BALANCED,
                        CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL,
                "test://" + id,
                CapabilityDescriptor.Health.HEALTHY,
                false,
                1,
                CapabilityDescriptor.NetworkPolicy.denied(),
                Set.of(),
                CapabilityDescriptor.CostPolicy.free(),
                CapabilityDescriptor.CachePolicy.disabled(),
                CapabilityDescriptor.IdempotencyPolicy.none());
    }
}
