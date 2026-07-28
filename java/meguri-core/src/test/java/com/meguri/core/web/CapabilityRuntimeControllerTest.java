package com.meguri.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.capability.McpSourceManager;
import com.meguri.core.capability.ToolProposal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRuntimeControllerTest {
    private static final String TOKEN = "test-admin-token-123456789";

    private CapabilityRuntimeFacade runtime;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        runtime = new CapabilityRuntimeFacade(8);
        McpSourceManager sources = new McpSourceManager(runtime, new ObjectMapper(), "");
        CapabilityRuntimeController controller =
                new CapabilityRuntimeController(runtime, sources, TOKEN);
        client = WebTestClient.bindToController(controller).build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void everyAdministrativeEndpointRequiresNontrivialCredential() {
        client.get().uri("/internal/v1/capabilities")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CAPABILITY_ADMIN_DISABLED");

        client.post().uri("/internal/v1/capabilities/meguri.read.default:disable")
                .header(CapabilityRuntimeController.ADMIN_HEADER, "wrong")
                .exchange()
                .expectStatus().isNotFound();

        client.get().uri("/internal/v1/capabilities")
                .header(CapabilityRuntimeController.ADMIN_HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].descriptor.id").exists();
    }

    @Test
    void springDoesNotRegisterAdministrativeSurfaceByDefault() {
        CapabilityRuntimeFacade isolated = new CapabilityRuntimeFacade(8);
        McpSourceManager sourceManager =
                new McpSourceManager(isolated, new ObjectMapper(), "");
        new ApplicationContextRunner()
                .withBean(CapabilityRuntimeFacade.class, () -> isolated)
                .withBean(McpSourceManager.class, () -> sourceManager)
                .withUserConfiguration(CapabilityRuntimeController.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityRuntimeController.class));
    }

    @Test
    void enableDisableAndDrainOperateOnRegistryWithoutChangingFrozenTurn() {
        CapabilityRuntimeFacade.TurnCapabilities oldTurn = runtime.freeze(context("old"));

        client.post().uri("/internal/v1/capabilities/meguri.read.default:drain")
                .header(CapabilityRuntimeController.ADMIN_HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.state").isEqualTo("DRAINING");

        assertThat(oldTurn.exposes(CapabilityRuntimeFacade.DEFAULT_READ_TOOL)).isTrue();
        assertThat(runtime.freeze(context("new"))
                .exposes(CapabilityRuntimeFacade.DEFAULT_READ_TOOL)).isFalse();

        client.post().uri("/internal/v1/capabilities/meguri.read.default/versions/1:enable")
                .header(CapabilityRuntimeController.ADMIN_HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk();
        assertThat(runtime.freeze(context("enabled"))
                .exposes(CapabilityRuntimeFacade.DEFAULT_READ_TOOL)).isTrue();
    }

    @Test
    void approvalAndAuditQueriesUseRuntimeStateMachine() {
        CapabilityRuntimeFacade.TurnCapabilities turn = runtime.freeze(new ExposurePlanner.ExposureContext(
                "turn-write", "tenant", "user", "client", Set.of(),
                CapabilityDescriptor.Mode.BALANCED,
                Set.of(CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL),
                1, true, CapabilityDescriptor.DataClassification.RESTRICTED));
        ToolProposal proposal = new ToolProposal(
                "turn-write", "trace-write", "tenant", "user", "client",
                CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL, Map.of(), Set.of(),
                "operation", "key", null, true, 1000);
        String approvalId = runtime.requestApproval(turn, proposal).approvalId();

        client.post().uri("/internal/v1/capabilities/approvals/{id}:resolve", approvalId)
                .header(CapabilityRuntimeController.ADMIN_HEADER, TOKEN)
                .bodyValue(Map.of("decision", "ACCEPT", "actor", "operator"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.decision").isEqualTo("ACCEPT");

        runtime.execute(turn, new ToolProposal(
                "turn-write", "trace-write", "tenant", "user", "client",
                CapabilityRuntimeFacade.DEFAULT_WRITE_TOOL, Map.of(), Set.of(),
                "operation", "key", approvalId, true, 1000));

        client.get().uri(uri -> uri.path("/internal/v1/capabilities/audit")
                        .queryParam("trace_id", "trace-write").build())
                .header(CapabilityRuntimeController.ADMIN_HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].traceId").isEqualTo("trace-write");
    }

    private static ExposurePlanner.ExposureContext context(String turnId) {
        return new ExposurePlanner.ExposureContext(
                turnId, "tenant", "user", "client", Set.of(),
                CapabilityDescriptor.Mode.BALANCED,
                Set.of(CapabilityRuntimeFacade.DEFAULT_READ_TOOL),
                1, true, CapabilityDescriptor.DataClassification.RESTRICTED);
    }
}
