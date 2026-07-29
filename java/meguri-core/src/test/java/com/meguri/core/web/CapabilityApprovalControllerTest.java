package com.meguri.core.web;

import com.meguri.core.capability.ApprovalService;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.capability.ToolProposal;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityApprovalControllerTest {
    @Test
    void owningUserCanResolvePendingTurnApproval() {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            var turn = runtime.freeze(new ExposurePlanner.ExposureContext(
                    "turn-1", "tenant", "user", "website", Set.of(),
                    CapabilityDescriptor.Mode.DEEP,
                    Set.of(CapabilityRuntimeFacade.DEFAULT_REMOTE_AGENT),
                    1, true, CapabilityDescriptor.DataClassification.INTERNAL));
            ToolProposal proposal = new ToolProposal(
                    "turn-1", "trace-1", "tenant", "user", "website",
                    CapabilityRuntimeFacade.DEFAULT_REMOTE_AGENT,
                    Map.of("task", "bounded"),
                    Set.of(), "operation-1", "idempotency-1",
                    null, true, 1_000);
            String approvalId = runtime.requestApproval(turn, proposal).approvalId();
            WebTestClient client = WebTestClient.bindToController(
                    new CapabilityApprovalController(
                            runtime,
                            new CoreIdentityVerifier(
                                    false, "tenant", "", "")))
                    .build();

            client.post()
                    .uri("/v1/approvals/{id}:resolve", approvalId)
                    .bodyValue(Map.of("decision", "ACCEPT"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.approval_id").isEqualTo(approvalId)
                    .jsonPath("$.decision").isEqualTo("ACCEPT");

            assertThat(runtime.findApproval(approvalId).orElseThrow().decision())
                    .isEqualTo(ApprovalService.Decision.ACCEPT);
        }
    }
}
