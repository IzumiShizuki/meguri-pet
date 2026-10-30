package com.meguri.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.runtime.ExpressionResolver;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.react.NormalizedReactObservation;
import com.meguri.core.react.RawReactObservation;
import com.meguri.core.react.ReactAction;
import com.meguri.core.react.ReactDecision;
import com.meguri.core.react.ReactPlannerDecision;
import com.meguri.core.react.ReactPlanningContext;
import com.meguri.core.runtime.InMemoryTurnJournal;
import com.meguri.core.runtime.NoopSessionContextPersistence;
import com.meguri.core.runtime.RuntimeStateMachine;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.runtime.TurnRecord;
import com.meguri.core.runtime.TurnStatus;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real turn/frozen capability/HTTP transport; no external provider or installed package. */
class ModelScopeCapabilityTurnIntegrationTest {
    @TempDir Path temporary;

    @Test
    void authorizedTurnDiscoversAndInspectsThroughFrozenCapabilityAndHttp() throws Exception {
        try (Fixture fixture = new Fixture(temporary, true)) {
            TurnRecord turn = fixture.run("allowed", "请在魔搭社区查找表格解析技能，仅查询", Set.of("skill:read"));

            assertThat(fixture.httpCalls).hasValue(2);
            assertThat(fixture.paths).containsExactly("/openapi/v1/skills", "/openapi/v1/skills/owner/table-reader");
            assertThat(fixture.plans).allSatisfy(plan -> assertThat(plan.scope().networkAllowed()).isTrue());
            assertThat(fixture.lastObservations()).hasSize(2).allSatisfy(observation -> {
                assertThat(observation.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
                assertThat(observation.summary()).contains("Table reader", "MIT", "owner/table-reader");
            });
            assertThat(turn.getProviderRequest().promptBlocks().toString()).contains("Table reader", "MIT");
            assertThat(fixture.journal.events(turn.getRequest().getSessionId()))
                    .filteredOn(event -> "skill.completed".equals(event.getType()))
                    .extracting(event -> event.getData().get("capability_id"))
                    .containsExactly("meguri.skill.discover", "meguri.skill.inspect");
            assertThat(fixture.repository.list()).isEmpty();
            assertThat(fixture.management.audit()).isEmpty();
        }
    }

    @Test
    void naturalRequestsEnterAuthorizedPublicReadWithoutAnExplicitExecutionMode() throws Exception {
        try (Fixture fixture = new Fixture(temporary, false)) {
            TurnRecord discovered = fixture.runNatural("natural-discover",
                    "请去魔搭社区寻找表格处理技能，只查询，不安装", Set.of("skill:read"));
            assertThat(discovered.getExecutionModeDecision().mode()).isEqualTo(TurnExecutionMode.AGENT);
            assertThat(discovered.getExecutionModeDecision().userExplicit()).isFalse();
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.lastObservations()).singleElement().satisfies(observation -> {
                assertThat(observation.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
                assertThat(observation.summary()).contains("Table reader", "MIT");
            });

            fixture.plans.clear();
            fixture.firstCapability = "meguri.skill.inspect";
            TurnRecord inspected = fixture.runNatural("natural-inspect",
                    "请在魔搭社区核实 owner/table-reader 的公开详情与许可", Set.of("skill:read"));
            assertThat(inspected.getExecutionModeDecision().mode()).isEqualTo(TurnExecutionMode.AGENT);
            assertThat(fixture.httpCalls).hasValue(2);
            assertThat(fixture.paths).containsExactly("/openapi/v1/skills", "/openapi/v1/skills/owner/table-reader");
            assertThat(fixture.repository.list()).isEmpty();
        }
    }

    @Test
    void naturalEntryDoesNotEscalateWithoutScopeOrForOfflineDiscussionAndQuotedRequests() throws Exception {
        try (Fixture fixture = new Fixture(temporary, false)) {
            String direct = "请去魔搭社区寻找表格处理技能";
            TurnRecord unscoped = fixture.runNatural("natural-unscoped", direct, Set.of("capability:read"));
            assertThat(unscoped.getExecutionModeDecision().mode()).isNotEqualTo(TurnExecutionMode.AGENT);
            int number = 0;
            for (String message : List.of(
                    "不要联网，请去魔搭社区寻找表格处理技能",
                    "请说明魔搭社区技能搜索 API 的实现方式",
                    "如何在魔搭社区搜索技能？",
                    "“请去魔搭社区搜索表格技能”这句话是什么意思？",
                    "```text\n请去魔搭社区搜索表格技能\n```",
                    "请安装魔搭社区的表格处理技能")) {
                TurnRecord turn = fixture.runNatural("natural-negative-" + number++, message, Set.of("skill:read"));
                assertThat(turn.getExecutionModeDecision().mode()).as(message).isNotEqualTo(TurnExecutionMode.AGENT);
            }
            TurnRecord explicitFast = fixture.runWithMode("natural-fast", direct, Set.of("skill:read"), TurnExecutionMode.FAST);
            assertThat(explicitFast.getExecutionModeDecision().mode()).isEqualTo(TurnExecutionMode.FAST);
            assertThat(fixture.httpCalls).hasValue(0);
            assertThat(fixture.plans).isEmpty();
        }
    }

    @Test
    void explicitOfflineRequestAndMissingScopeProduceNoHttpEvenWhenPlannerProposesDiscovery() throws Exception {
        try (Fixture fixture = new Fixture(temporary, false)) {
            fixture.run("offline", "不要联网，请仅检查本地技能", Set.of("skill:read"));
            assertThat(fixture.httpCalls).hasValue(0);
            assertThat(fixture.plans).allSatisfy(plan -> {
                assertThat(plan.scope().networkAllowed()).isFalse();
                assertThat(plan.exposedCapabilities()).extracting(value -> value.id())
                        .doesNotContain("meguri.skill.discover", "meguri.skill.inspect");
            });
            fixture.plans.clear();
            fixture.run("scope-denied", "请查询魔搭社区技能", Set.of());
            assertThat(fixture.httpCalls).hasValue(0);
            assertThat(fixture.plans).allSatisfy(plan -> assertThat(plan.exposedCapabilities())
                    .extracting(value -> value.id()).doesNotContain("meguri.skill.discover", "meguri.skill.inspect"));
        }
    }

    @Test
    void publicTransportFailureDoesNotPreventNextIndependentTurnFromReturningMetadata() throws Exception {
        try (Fixture fixture = new Fixture(temporary, false)) {
            fixture.failTransport.set(true);
            TurnRecord failed = fixture.run("failure", "请查询魔搭社区技能", Set.of("skill:read"));
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.lastObservations()).singleElement()
                    .satisfies(observation -> {
                        assertThat(observation.status()).isEqualTo(RawReactObservation.Status.FAILED);
                        assertThat(observation.errorCode()).isEqualTo("MODELSCOPE_SOURCE_UNAVAILABLE");
                        assertThat(observation.retryable()).isTrue();
                        assertThat(observation.summary()).contains("MODELSCOPE_SOURCE_UNAVAILABLE");
                    });
            assertThat(failed.getProviderRequest().promptBlocks().toString()).contains("MODELSCOPE_SOURCE_UNAVAILABLE");

            fixture.failTransport.set(false);
            fixture.plans.clear();
            fixture.run("recovery", "请查询魔搭社区技能", Set.of("skill:read"));
            assertThat(fixture.httpCalls).hasValue(2);
            assertThat(fixture.lastObservations()).singleElement().satisfies(observation -> {
                assertThat(observation.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
                assertThat(observation.summary()).contains("Table reader", "MIT");
            });
            assertThat(fixture.repository.list()).isEmpty();
        }
    }

    @Test
    void networkDecisionBelongsToAnAuthenticFrozenPlanAndDefaultsToDenied() {
        try (CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(8)) {
            var empty = capabilities.freezeEmpty("empty");
            assertThat(capabilities.networkAllowed(empty)).isFalse();
            var denied = capabilities.freeze(new ExposurePlanner.ExposureContext(
                    "denied", "tenant", "user", "website", Set.of(), CapabilityDescriptor.Mode.DEEP,
                    Set.of(), 1, false, CapabilityDescriptor.DataClassification.INTERNAL));
            assertThat(capabilities.networkAllowed(denied)).isFalse();
            var allowed = capabilities.freeze(new ExposurePlanner.ExposureContext(
                    "allowed", "tenant", "user", "website", Set.of(), CapabilityDescriptor.Mode.DEEP,
                    Set.of(), 1, true, CapabilityDescriptor.DataClassification.INTERNAL));
            assertThat(capabilities.networkAllowed(allowed)).isTrue();
            var forged = new CapabilityRuntimeFacade.TurnCapabilities("forged", allowed.turnId(),
                    allowed.snapshotId(), allowed.frozenAt(), allowed.exposed());
            assertThatThrownBy(() -> capabilities.networkAllowed(forged)).isInstanceOf(RuntimeException.class);
            capabilities.release(allowed);
            assertThatThrownBy(() -> capabilities.networkAllowed(allowed)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void approvedMcpStructuredResultReachesChatWhileMissingApprovalIsExplainedWithoutCallingTool() throws Exception {
        for (boolean approve : List.of(false, true)) {
            try (Fixture fixture = new Fixture(temporary, false)) {
                AtomicInteger calls = new AtomicInteger();
                var descriptor = new com.meguri.core.capability.McpCapabilityNormalizer().normalize("fixture",
                        Map.of("name", "lookup", "annotations", Map.of("readOnlyHint", true),
                                "inputSchema", Map.of("type", "object", "properties", Map.of())), 4);
                fixture.capabilities.register(descriptor, (input, execution) -> {
                    calls.incrementAndGet();
                    return Map.of("answer", "STRUCTURED_MCP_CHAT_EVIDENCE", "source", "https://docs.example/public");
                });
                fixture.runtime.configureLimitedReactPlanner(context -> {
                    fixture.plans.add(context);
                    ReactAction action = null;
                    if (context.roundIndex() == 1) {
                        String approvalId = null;
                        if (approve) {
                            var scope = context.scope();
                            var frozen = fixture.runtime.getTurn(scope.turnId()).getRuntimeCapabilities();
                            var proposal = new com.meguri.core.capability.ToolProposal(scope.turnId(), scope.traceId(),
                                    scope.tenantId(), scope.userId(), scope.clientId(), descriptor.id(), Map.of(),
                                    scope.scopes(), "synthetic-operation", null, null, scope.networkAllowed(), 100);
                            approvalId = fixture.capabilities.requestApproval(frozen, proposal).approvalId();
                            fixture.capabilities.resolveApproval(approvalId, com.meguri.core.capability.ApprovalService.Decision.ACCEPT,
                                    "synthetic-test-user");
                        }
                        action = new ReactAction(descriptor.id(), Map.of(), "synthetic-operation", null, approvalId);
                    }
                    return Mono.just(new ReactPlannerDecision(action == null ? ReactDecision.FINALIZE : ReactDecision.CONTINUE,
                            context.goal(), "TEST_MCP", action, null));
                });
                var turn = fixture.run("mcp-chat-" + approve, "请联网查询公开文档", Set.of("mcp:fixture"));
                String chatContext = turn.getProviderRequest().promptBlocks().toString();
                if (approve) {
                    assertThat(calls).hasValue(1);
                    assertThat(chatContext).contains("STRUCTURED_MCP_CHAT_EVIDENCE", "https://docs.example/public");
                    assertThat(turn.getProviderRequest().promptBlocks())
                            .filteredOn(block -> block.content().contains("STRUCTURED_MCP_CHAT_EVIDENCE"))
                            .singleElement().satisfies(block -> {
                                assertThat(block.role()).isEqualTo(com.meguri.core.persona.prompt.PromptPolicyComposer.Role.USER_DATA);
                                assertThat(block.trust()).isEqualTo(com.meguri.core.persona.prompt.PromptPolicyComposer.Trust.UNTRUSTED);
                            });
                    assertThat(turn.getResult().response().reply()).isEqualTo("STRUCTURED_MCP_CHAT_EVIDENCE");
                } else {
                    assertThat(calls).hasValue(0);
                    assertThat(chatContext).contains("MCP_READ_APPROVAL_REQUIRED").doesNotContain("STRUCTURED_MCP_CHAT_EVIDENCE");
                }
                assertThat(fixture.httpCalls).hasValue(0);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger httpCalls = new AtomicInteger();
        final AtomicBoolean failTransport = new AtomicBoolean();
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<ReactPlanningContext> plans = new CopyOnWriteArrayList<>();
        final InMemorySkillCatalogRepository repository = new InMemorySkillCatalogRepository();
        final CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        final HttpServer server;
        final HttpClient client = HttpClient.newHttpClient();
        final InMemoryTurnJournal journal;
        final SkillManagementService management;
        final TurnOrchestrator runtime;
        String firstCapability = "meguri.skill.discover";

        Fixture(Path temporary, boolean inspect) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/openapi/v1/skills", exchange -> {
                httpCalls.incrementAndGet();
                paths.add(exchange.getRequestURI().getPath());
                String item = """
                        {"id":"owner/table-reader","display_name":"Table reader",
                         "description":"Read tabular metadata","license":"MIT"}
                        """;
                String body = exchange.getRequestURI().getPath().endsWith("/skills")
                        ? "{\"data\":{\"skills\":[" + item + "]}}"
                        : "{\"data\":{\"skill\":" + item + "}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failTransport.get() ? 503 : 200, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
                exchange.close();
            });
            server.start();
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            // The production plugin still validates/constructs the fixed HTTPS host.
            // Only this injected transport forwards its public GET to the loopback fixture.
            var source = new ModelScopeSkillSourcePlugin(request -> {
                assertThat(request.uri().getHost()).isEqualTo("modelscope.cn");
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.headers().firstValue("X-Meguri-Internal-Token")).isEmpty();
                URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + request.uri().getRawPath()
                        + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery()));
                var response = client.send(HttpRequest.newBuilder(local).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new ModelScopeSkillSourcePlugin.TransportResponse(response.statusCode(), response.body());
            }, mapper, URI.create("https://modelscope.cn"), URI.create("http://127.0.0.1:8000"),
                    "fixture-token-unused", Duration.ofSeconds(2));
            management = new SkillManagementService(new SkillSourceRegistry(List.of(source)), repository,
                    new SkillPackageValidator(Set.of(), name -> false), new SkillPackageStore(temporary));
            journal = new InMemoryTurnJournal(mapper);
            LlmProvider provider = new LlmProvider() {
                @Override public Mono<LlmResponse> respond(com.meguri.core.llm.ProviderRequest request) {
                    return Mono.just(new LlmResponse(request.promptBlocks().toString().contains("STRUCTURED_MCP_CHAT_EVIDENCE")
                            ? "STRUCTURED_MCP_CHAT_EVIDENCE" : "fixture reply"));
                }
                @Override public Mono<LlmResponse> respond(TurnRequest request, com.meguri.core.dto.RuntimeState state,
                        List<String> canon, List<String> memories, List<String> recent) {
                    throw new AssertionError("canonical provider boundary required");
                }
            };
            runtime = new TurnOrchestrator(provider, (query, state, limit) -> List.of(), new RuntimeStateMachine(),
                    new ExpressionResolver(), Duration.ofMillis(1), mapper, new NoopMemoryGateway(),
                    new NoopWebSearchGateway(), TrainingFeedbackService.disabled(mapper),
                    WeatherConversationService.disabled(), journal, new NoopSessionContextPersistence(), capabilities);
            runtime.configureExternalSkills(new SkillSelectionService(management), new SkillDisclosureService(management));
            runtime.configurePerformanceFeatures(true, true, true);
            runtime.configureLimitedReactPlanner(context -> {
                plans.add(context);
                ReactAction action = context.roundIndex() == 1
                        ? new ReactAction(firstCapability, firstCapability.equals("meguri.skill.inspect")
                                ? Map.of("external_id", "owner/table-reader") : Map.of("query", "tabular"))
                        : inspect && context.roundIndex() == 2
                                ? new ReactAction("meguri.skill.inspect", Map.of("external_id", "owner/table-reader")) : null;
                return Mono.just(new ReactPlannerDecision(action == null ? ReactDecision.FINALIZE : ReactDecision.CONTINUE,
                        context.goal(), action == null ? "DONE" : "PUBLIC_METADATA", action,
                        action == null ? "fixture complete" : null));
            });
        }

        TurnRecord run(String session, String message, Set<String> scopes) throws Exception {
            return runWithMode(session, message, scopes, TurnExecutionMode.AGENT);
        }

        TurnRecord runNatural(String session, String message, Set<String> scopes) throws Exception {
            return runWithMode(session, message, scopes, null);
        }

        TurnRecord runWithMode(String session, String message, Set<String> scopes, TurnExecutionMode mode) throws Exception {
            TurnRequest request = new TurnRequest("fictional-capability-user", "website", session, message,
                    List.of(), new ClientCapabilities(), null, null, false)
                    .withAuthorizedCapabilityScopes(scopes);
            if (mode != null) request = request.withRequestedExecutionMode(mode);
            TurnRecord turn = runtime.start(request);
            turn.getDone().get(10, TimeUnit.SECONDS);
            assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
            return turn;
        }

        List<NormalizedReactObservation> lastObservations() { return plans.getLast().observations(); }

        @Override public void close() {
            runtime.reset();
            capabilities.close();
            client.close();
            server.stop(0);
        }
    }
}
