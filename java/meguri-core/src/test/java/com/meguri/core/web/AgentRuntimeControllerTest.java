package com.meguri.core.web;

import com.meguri.core.agent.AgentRuntimeAssembly;
import com.meguri.core.agent.AgentRuntimeFactory;
import com.meguri.core.agent.AgentRuntimePolicy;
import com.meguri.core.agent.AgentRuntimeState;
import com.meguri.core.agent.AgentTaskContext;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeControllerTest {
    private static final Instant REQUEST_DEADLINE =
            Instant.now().plus(Duration.ofMinutes(2));
    private AgentRuntimeAssembly assembly;
    private AgentRuntimePolicy policy;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        assembly = AgentRuntimeFactory.createDefault();
        policy = new AgentRuntimePolicy(
                Duration.ofMinutes(5),
                new AgentTaskContext.Budget(
                        1_000, 8, new BigDecimal("5.00"), 3, 4),
                Set.of("local"));
        CoreIdentityVerifier verifier =
                new CoreIdentityVerifier(false, "tenant-test", "", "");
        client = WebTestClient.bindToController(
                        new AgentRuntimeController(assembly, verifier, policy))
                .build();
    }

    @AfterEach
    void tearDown() {
        assembly.close();
    }

    @Test
    void durableInvokeStatusCallbackAndCancelUseRuntimeStateMachine() {
        Map<?, ?> accepted = invoke("durable-1", "first durable")
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .value(body -> {
                    assertThat(body.get("status")).isEqualTo("ACCEPTED_DURABLE");
                    assertThat(body.get("provider_mode")).isEqualTo("IN_MEMORY_FALLBACK");
                })
                .returnResult()
                .getResponseBody();
        assertThat(accepted).isNotNull();
        String taskId = String.valueOf(accepted.get("task_id"));
        String remoteTaskId = String.valueOf(accepted.get("remote_task_id"));

        var resource = assembly.registry()
                .resource(assembly.config().resourceId()).orElseThrow();
        assertThat(resource.availableSubmitPermits())
                .isEqualTo(assembly.config().submitMaxConcurrency());
        assertThat(resource.availableInFlightPermits())
                .isEqualTo(assembly.config().maxInFlightTasks() - 1);

        client.get().uri("/v1/agent/tasks/{id}", taskId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("WAITING_EXTERNAL")
                .jsonPath("$.tenant_id").isEqualTo("tenant-test");

        client.post().uri("/v1/agent/tasks/{id}/callback", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "remote_task_id", remoteTaskId,
                        "result", Map.of(
                                "schema_id", AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                                "source_agent_id", AgentRuntimeFactory.DEFAULT_AGENT_ID,
                                "payload", Map.of("answer", "callback answer"),
                                "sensitive", false)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.result.payload.answer").isEqualTo("callback answer");
        assertThat(assembly.store().findTask(taskId).orElseThrow().status())
                .isEqualTo(AgentRuntimeState.AgentStatus.SUCCEEDED);
        assertFullCapacity();
        invoke("durable-1", "first durable")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task_id").isEqualTo(taskId)
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.result.payload.answer").isEqualTo("callback answer")
                .jsonPath("$.reason").isEqualTo("idempotent replay");

        Map<?, ?> cancellable = invoke("durable-2", "cancel durable")
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        String cancellableTaskId = String.valueOf(cancellable.get("task_id"));
        client.post().uri("/v1/agent/tasks/{id}/cancel", cancellableTaskId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED");
        invoke("durable-2", "cancel durable")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task_id").isEqualTo(cancellableTaskId)
                .jsonPath("$.status").isEqualTo("CANCELLED")
                .jsonPath("$.reason").isEqualTo("idempotent replay");
        assertFullCapacity();
    }

    @Test
    void idempotencyReplaysSameTaskAndRejectsDifferentPayload() {
        Map<String, Object> sameRequest = request("turn-agent", "same payload");
        String firstTask = String.valueOf(invokeRaw("same-key", sameRequest)
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult().getResponseBody().get("task_id"));
        String replayedTask = String.valueOf(invokeRaw("same-key", sameRequest)
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult().getResponseBody().get("task_id"));
        assertThat(replayedTask).isEqualTo(firstTask);
        assertThat(assembly.gateway().metrics().totalSubmits()).isEqualTo(1);

        invoke("same-key", "different payload")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("AGENT_POLICY_DENIED");
        assertThat(assembly.gateway().metrics().totalSubmits()).isEqualTo(1);
    }

    @Test
    void deadlineBudgetScopeAndCallbackIdentityExpansionFailClosed() {
        Map<String, Object> expanded = request("expansion", "must fail");
        expanded.put("deadline", Instant.now().plus(Duration.ofMinutes(6)).toString());
        invokeRaw("expanded-deadline", expanded)
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error.code").isEqualTo("AGENT_POLICY_DENIED");

        expanded = request("expansion", "must fail");
        expanded.put("allowed_capabilities", Set.of("local", "admin"));
        invokeRaw("expanded-scope", expanded)
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error.code").isEqualTo("AGENT_POLICY_DENIED");

        expanded = request("expansion", "must fail");
        expanded.put("budget", budget(1_001));
        invokeRaw("expanded-budget", expanded)
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error.code").isEqualTo("AGENT_POLICY_DENIED");
        assertThat(assembly.gateway().metrics().totalSubmits()).isZero();

        Map<?, ?> accepted = invoke("callback-mismatch", "callback")
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        String taskId = String.valueOf(accepted.get("task_id"));
        client.post().uri("/v1/agent/tasks/{id}/callback", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("result", Map.of(
                        "schema_id", AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                        "source_agent_id", "forged-agent",
                        "payload", Map.of("answer", "bad"),
                        "sensitive", false)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error.code").isEqualTo("AGENT_POLICY_DENIED");
        assertThat(assembly.store().findTask(taskId).orElseThrow().status())
                .isEqualTo(AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
    }

    @Test
    void hostedIdentityMustOwnInvokeAndTaskOperations() {
        CoreIdentityVerifier hostedVerifier =
                new CoreIdentityVerifier(true, "tenant-hosted", "", "secret");
        WebTestClient hosted = WebTestClient.bindToController(
                        new AgentRuntimeController(assembly, hostedVerifier, policy))
                .build();
        Map<String, Object> body = request("hosted", "owned");
        body.put("identity", Map.of(
                "user_id", "owner",
                "client_id", "website",
                "session_id", "agent-session"));

        Map<?, ?> accepted = hosted.post().uri("/v1/agent/tasks")
                .header("Idempotency-Key", "hosted-key")
                .headers(headers -> identityHeaders(headers, "owner"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        String taskId = String.valueOf(accepted.get("task_id"));

        hosted.get().uri("/v1/agent/tasks/{id}", taskId)
                .headers(headers -> identityHeaders(headers, "attacker"))
                .exchange()
                .expectStatus().isForbidden();
        hosted.get().uri("/v1/agent/tasks/{id}", taskId)
                .headers(headers -> identityHeaders(headers, "owner", "other-session"))
                .exchange()
                .expectStatus().isForbidden();
        hosted.post().uri("/v1/agent/tasks/{id}/cancel", taskId)
                .headers(headers -> identityHeaders(headers, "owner"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    private WebTestClient.ResponseSpec invoke(String key, String taskBrief) {
        return invokeRaw(key, request("turn-agent", taskBrief));
    }

    private WebTestClient.ResponseSpec invokeRaw(String key, Map<String, Object> body) {
        return client.post().uri("/v1/agent/tasks")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static Map<String, Object> request(String turnId, String taskBrief) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("identity", Map.of(
                "user_id", "user-test",
                "client_id", "website",
                "session_id", "agent-session"));
        body.put("turn_id", turnId);
        body.put("parent_task_id", "root");
        body.put("task_brief", taskBrief);
        body.put("mode", "DURABLE_ASYNC");
        body.put("required", true);
        body.put("deadline", REQUEST_DEADLINE.toString());
        body.put("budget", budget(500));
        body.put("allowed_capabilities", Set.of("local"));
        body.put("references", Map.of("source", "controller-test"));
        body.put("result_schema", Map.of(
                "schema_id", AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                "required_fields", Map.of("answer", "STRING")));
        return body;
    }

    private static Map<String, Object> budget(long maxTokens) {
        return Map.of(
                "max_tokens", maxTokens,
                "max_tool_calls", 4,
                "max_cost", new BigDecimal("2.00"),
                "max_depth", 2,
                "max_children", 2);
    }

    private static void identityHeaders(HttpHeaders headers, String userId) {
        identityHeaders(headers, userId, "agent-session");
    }

    private static void identityHeaders(HttpHeaders headers, String userId, String sessionId) {
        headers.setBearerAuth("secret");
        headers.set("X-Meguri-Tenant-ID", "tenant-hosted");
        headers.set("X-Meguri-User-ID", userId);
        headers.set("X-Meguri-Client-ID", "website");
        headers.set("X-Meguri-Session-ID", sessionId);
    }

    private void assertFullCapacity() {
        var resource = assembly.registry()
                .resource(assembly.config().resourceId()).orElseThrow();
        assertThat(resource.availableSubmitPermits())
                .isEqualTo(assembly.config().submitMaxConcurrency());
        assertThat(resource.availableInFlightPermits())
                .isEqualTo(assembly.config().maxInFlightTasks());
    }
}
