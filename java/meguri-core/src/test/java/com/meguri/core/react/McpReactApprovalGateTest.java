package com.meguri.core.react;

import com.meguri.core.agent.*;
import com.meguri.core.capability.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class McpReactApprovalGateTest {
    @Test void exceptionDoesNotAdmitOtherKindsMissingScopesNetworkOrApprovalPolicy() {
        var base = new McpCapabilityNormalizer().normalize("fixture", Map.of("name", "lookup",
                "annotations", Map.of("readOnlyHint", true), "inputSchema", Map.of("type", "object")), 4);
        var validator = new ActionProposalValidator();
        for (int variation = 0; variation < 6; variation++) {
            var descriptor = new CapabilityDescriptor(variation == 0 ? "unrelated.lookup" : base.id(), base.version(),
                    variation == 1 ? CapabilityDescriptor.Kind.RESOURCE : variation == 2 ? CapabilityDescriptor.Kind.WRITE_TOOL : base.kind(),
                    base.owner(), base.inputSchema(), base.outputSchema(), variation == 3 ? Set.of() : base.scopes(),
                    variation == 2 ? CapabilityDescriptor.SideEffect.WRITE : base.sideEffect(),
                    variation == 4 ? CapabilityDescriptor.ApprovalRequirement.NONE : base.approval(),
                    base.timeout(), variation == 2 ? CapabilityDescriptor.RetryPolicy.none() : base.retry(),
                    base.concurrency(), base.allowedModes(), base.dataClassification(), base.resultTrust(),
                    base.implementationRef(), base.health(), base.deprecated(), base.minimumProtocol(),
                    variation == 5 ? CapabilityDescriptor.NetworkPolicy.denied() : base.network(), base.requiredSecrets(),
                    base.cost(), base.cache(), base.idempotency());
            assertThat(validator.validate(new ReactAction(descriptor.id(), Map.of(), "op", null, "approval"),
                    List.of(descriptor), 100).accepted()).as("policy variant %s", variation).isFalse();
        }
        assertThat(validator.validate(new ReactAction(base.id(), Map.of(), null, null, "approval"), List.of(base), 100).reasonCode())
                .isEqualTo("MCP_READ_APPROVAL_REQUIRED");
    }

    @Test void approvedMcpReadReachesUntrustedObservationButMissingForgedAndChangedApprovalsDoNot() {
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new CapabilityRuntimeFacade(8); var dispatcher = new StepDispatcher(1,1,1,1)) {
            var descriptor = new McpCapabilityNormalizer().normalize("fixture", Map.of("name", "lookup",
                    "annotations", Map.of("readOnlyHint", true),
                    "inputSchema", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")), "required", List.of("query")),
                    "outputSchema", Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")), "required", List.of("value"))), 4);
            runtime.register(descriptor, (input, context) -> { calls.incrementAndGet(); return Map.of("value", "STRUCTURED_MCP_RESULT"); });
            var frozen = runtime.freeze(new ExposurePlanner.ExposureContext("turn", "tenant", "user", "client",
                    Set.of("mcp:fixture"), CapabilityDescriptor.Mode.DEEP, Set.of(descriptor.id()), 1, true,
                    CapabilityDescriptor.DataClassification.INTERNAL));
            var validator = new ActionProposalValidator();
            assertThat(validator.validate(new ReactAction(descriptor.id(), Map.of("query", "public")),
                    List.of(descriptor), 10).accepted()).isFalse();
            var proposal = new ToolProposal("turn", "trace", "tenant", "user", "client", descriptor.id(),
                    Map.of("query", "public"), Set.of("mcp:fixture"), "op", null, null, true, 10);
            String approval = runtime.requestApproval(frozen, proposal).approvalId();
            runtime.resolveApproval(approval, ApprovalService.Decision.ACCEPT, "synthetic-user");
            var action = new ReactAction(descriptor.id(), Map.of("query", "public"), "op", null, approval);
            var valid = validator.validate(action, List.of(descriptor), 10);
            assertThat(valid.accepted()).as(valid.reasonCode()).isTrue();
            var executor = new CapabilityRuntimeReactActionExecutor(runtime, frozen, dispatcher, Clock.systemUTC());
            var context = new ReactExecutionContext(new ReactInvocationScope("turn", "trace", "tenant", "user", "client",
                    Set.of("mcp:fixture"), frozen.snapshotId(), true), 1, action.digest(), Instant.now().plusSeconds(5), 5000, 10, new CancellationToken());
            var accepted = executor.execute(valid.action(), context).block();
            assertThat(accepted.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(accepted.summary()).contains("STRUCTURED_MCP_RESULT", "untrusted");
            assertThat(accepted.data()).containsOnlyKeys("result_digest");
            for (var invalid : List.of(new ReactAction(descriptor.id(), Map.of("query", "public"), "op", null, "forged"),
                    new ReactAction(descriptor.id(), Map.of("query", "changed"), "op", null, approval))) {
                var checked = validator.validate(invalid, List.of(descriptor), 10);
                assertThat(checked.accepted()).isTrue(); // Shape only; runtime verifies the authentic exact proposal.
                var rejected = executor.execute(checked.action(), context).block();
                assertThat(rejected.errorCode()).isEqualTo("APPROVAL_REQUIRED");
            }
            for (var decision : List.of(ApprovalService.Decision.PENDING, ApprovalService.Decision.DECLINE, ApprovalService.Decision.CANCEL)) {
                String operation = "op-" + decision;
                var pending = new ToolProposal("turn", "trace", "tenant", "user", "client", descriptor.id(),
                        Map.of("query", "public"), Set.of("mcp:fixture"), operation, null, null, true, 10);
                String approvalId = runtime.requestApproval(frozen, pending).approvalId();
                if (decision != ApprovalService.Decision.PENDING) runtime.resolveApproval(approvalId, decision, "synthetic-user");
                var checked = validator.validate(new ReactAction(descriptor.id(), Map.of("query", "public"), operation, null, approvalId), List.of(descriptor), 10);
                var rejected = executor.execute(checked.action(), context).block();
                assertThat(rejected.status()).isEqualTo(RawReactObservation.Status.FAILED);
                assertThat(rejected.errorCode()).isEqualTo(decision == ApprovalService.Decision.PENDING ? "APPROVAL_REQUIRED"
                        : decision == ApprovalService.Decision.DECLINE ? "APPROVAL_DECLINED" : "APPROVAL_CANCELLED");
            }
            assertThat(calls).hasValue(1);
        }
    }
}
