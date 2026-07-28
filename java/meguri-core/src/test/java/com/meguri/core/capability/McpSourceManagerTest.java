package com.meguri.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSourceManagerTest {

    @Test
    void hotUpdateOnlyAffectsNewTurnsAndRetiresOldVersionAfterRelease() {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            FakeAdapter adapter = new FakeAdapter();
            adapter.tools = List.of(tool("lookup", true, "string"));
            McpSourceManager manager = manager(runtime, adapter);

            McpSourceManager.SourceStatus registered = manager.register(configuration());
            String capabilityId = "mcp.alpha.lookup";
            CapabilityRuntimeFacade.TurnCapabilities oldTurn =
                    runtime.freeze(context("old", capabilityId));
            String oldVersion = oldTurn.exposed().stream()
                    .filter(ref -> ref.id().equals(capabilityId)).findFirst().orElseThrow().version();

            adapter.tools = List.of(tool("lookup", true, "number"));
            McpSourceManager.SourceStatus updated = manager.sync("alpha");
            CapabilityRuntimeFacade.TurnCapabilities newTurn =
                    runtime.freeze(context("new", capabilityId));
            String newVersion = newTurn.exposed().stream()
                    .filter(ref -> ref.id().equals(capabilityId)).findFirst().orElseThrow().version();

            assertThat(registered.state()).isEqualTo(McpSourceManager.SourceState.CONNECTED);
            assertThat(newVersion).isNotEqualTo(oldVersion);
            assertThat(updated.capabilityVersions()).containsEntry(capabilityId, newVersion);
            assertThat(oldTurn.exposes(capabilityId)).isTrue();
            assertThat(runtime.catalogEntries())
                    .filteredOn(entry -> entry.descriptor().id().equals(capabilityId))
                    .hasSize(2)
                    .anySatisfy(entry -> {
                        assertThat(entry.descriptor().version()).isEqualTo(oldVersion);
                        assertThat(entry.draining()).isTrue();
                        assertThat(entry.retainedByFrozenTurn()).isTrue();
                    });

            runtime.release(oldTurn);
            assertThat(runtime.catalogEntries())
                    .filteredOn(entry -> entry.descriptor().id().equals(capabilityId))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.descriptor().version())
                            .isEqualTo(newVersion));
        }
    }

    @Test
    void invalidRemoteRefreshFailsClosedWithoutChangingActiveSnapshot() {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            FakeAdapter adapter = new FakeAdapter();
            adapter.tools = List.of(tool("lookup", true, "string"));
            McpSourceManager manager = manager(runtime, adapter);
            manager.register(configuration());
            CapabilityRuntimeFacade.TurnCapabilities before =
                    runtime.freeze(context("before", "mcp.alpha.lookup"));

            Map<String, Object> invalid = tool("broken", true, "string");
            invalid.put("secretToken", "must-not-be-accepted");
            adapter.tools = List.of(tool("new-tool", true, "string"), invalid);

            assertThatThrownBy(() -> manager.sync("alpha"))
                    .isInstanceOf(McpSourceManager.McpSourceException.class)
                    .hasMessage("MCP source refresh failed closed");
            CapabilityRuntimeFacade.TurnCapabilities after =
                    runtime.freeze(context("after", "mcp.alpha.lookup"));

            assertThat(after.exposed()).contains(new CapabilityRuntimeFacade.CapabilityRef(
                    "mcp.alpha.lookup",
                    before.exposed().stream()
                            .filter(ref -> ref.id().equals("mcp.alpha.lookup"))
                            .findFirst().orElseThrow().version()));
            assertThat(runtime.catalogEntries())
                    .noneMatch(entry -> entry.descriptor().id().equals("mcp.alpha.new-tool"));
            assertThat(manager.status("alpha").state())
                    .isEqualTo(McpSourceManager.SourceState.FAILED);
        }
    }

    @Test
    void removedToolDrainsForNewTurnsButFrozenTurnRetainsGrant() {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            FakeAdapter adapter = new FakeAdapter();
            adapter.tools = List.of(tool("lookup", true, "string"));
            McpSourceManager manager = manager(runtime, adapter);
            manager.register(configuration());
            CapabilityRuntimeFacade.TurnCapabilities frozen =
                    runtime.freeze(context("frozen", "mcp.alpha.lookup"));

            adapter.tools = List.of();
            manager.sync("alpha");

            assertThat(frozen.exposes("mcp.alpha.lookup")).isTrue();
            assertThat(runtime.freeze(context("later", "mcp.alpha.lookup"))
                    .exposes("mcp.alpha.lookup")).isFalse();
        }
    }

    @Test
    void changedRemoteToolCannotUseFrozenReadGrantForNewWriteSemantics() {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            FakeAdapter adapter = new FakeAdapter();
            adapter.tools = List.of(tool("lookup", true, "string"));
            McpSourceManager manager = manager(runtime, adapter);
            manager.register(configuration());
            CapabilityRuntimeFacade.TurnCapabilities frozen =
                    runtime.freeze(context("frozen-risk", "mcp.alpha.lookup"));

            adapter.tools = List.of(tool("lookup", false, "string"));
            manager.sync("alpha");
            ToolProposal staleProposal = new ToolProposal(
                    "frozen-risk", "trace-frozen-risk", "tenant", "user", "client",
                    "mcp.alpha.lookup", Map.of("query", "value"), Set.of("mcp:alpha"),
                    null, null, null, true, 100);
            ApprovalService.Approval requested =
                    runtime.requestApproval(frozen, staleProposal);
            String approvalId = runtime.resolveApproval(
                    requested.approvalId(), ApprovalService.Decision.ACCEPT,
                    "user").approvalId();
            CapabilityResult stale = runtime.execute(frozen, new ToolProposal(
                    staleProposal.turnId(), staleProposal.traceId(),
                    staleProposal.tenantId(), staleProposal.userId(),
                    staleProposal.clientId(), staleProposal.capabilityId(),
                    staleProposal.input(), staleProposal.scopes(), null, null,
                    approvalId, staleProposal.networkAllowed(),
                    staleProposal.maximumCostUnits()));

            assertThat(stale.errorCode()).isEqualTo("IMPLEMENTATION_FAILED");
            assertThat(adapter.invocations).hasValue(0);
            CapabilityRuntimeFacade.TurnCapabilities current =
                    runtime.freeze(context("current-risk", "mcp.alpha.lookup"));
            assertThat(runtime.exposedDescriptors(current))
                    .filteredOn(value -> value.id().equals("mcp.alpha.lookup"))
                    .singleElement()
                    .satisfies(value -> {
                        assertThat(value.kind())
                                .isEqualTo(CapabilityDescriptor.Kind.WRITE_TOOL);
                        assertThat(value.approval())
                                .isEqualTo(CapabilityDescriptor.ApprovalRequirement.ALWAYS);
                    });
        }
    }

    @Test
    void sourceConfigurationRequiresTlsAndEnvironmentBackedSecrets() {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            FakeAdapter adapter = new FakeAdapter();
            McpSourceManager manager = manager(runtime, adapter);

            assertThatThrownBy(() -> manager.register(new McpSourceManager.SourceConfiguration(
                    "alpha", URI.create("http://remote.example/mcp"), 1,
                    null, Map.of(), false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
            assertThatThrownBy(() -> manager.register(new McpSourceManager.SourceConfiguration(
                    "alpha", URI.create("https://remote.example/mcp"), 1,
                    null, Map.of("Authorization", "secret"), false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authorizationEnvironment");
            assertThatThrownBy(() -> manager.register(new McpSourceManager.SourceConfiguration(
                    "alpha", URI.create("https://remote.example/mcp"), 1,
                    null, Map.of("Host", "internal.service"), false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authorizationEnvironment");
        }
    }

    @Test
    void configuredSourcesLoadThroughTheSameValidatedHotUpdatePath() throws Exception {
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            FakeAdapter adapter = new FakeAdapter();
            adapter.tools = List.of(tool("lookup", true, "string"));
            ObjectMapper mapper = new ObjectMapper();
            String configuration = mapper.writeValueAsString(List.of(configuration()));
            McpSourceManager manager = new McpSourceManager(
                    runtime, (source, authorization) -> adapter,
                    mapper, name -> "Bearer hidden", configuration);

            manager.loadConfiguredSources();

            assertThat(manager.statuses()).singleElement().satisfies(status -> {
                assertThat(status.configuration().authorizationEnvironment())
                        .isEqualTo("MCP_ALPHA_TOKEN");
                assertThat(status.capabilityVersions()).containsKey("mcp.alpha.lookup");
                assertThat(status.toString()).doesNotContain("Bearer hidden");
            });
        }
    }

    @Test
    void durableConfigurationReconnectsAfterRestartAndRemovalPreventsReload() {
        InMemoryMcpSourceStore store = new InMemoryMcpSourceStore();
        FakeAdapter adapter = new FakeAdapter();
        adapter.tools = List.of(tool("lookup", true, "string"));

        try (CapabilityRuntimeFacade firstRuntime = new CapabilityRuntimeFacade(8)) {
            manager(firstRuntime, adapter, store).register(configuration());
        }

        try (CapabilityRuntimeFacade restartedRuntime = new CapabilityRuntimeFacade(8)) {
            McpSourceManager restarted = manager(restartedRuntime, adapter, store);
            restarted.loadConfiguredSources();

            assertThat(restarted.statuses()).singleElement().satisfies(status -> {
                assertThat(status.state()).isEqualTo(McpSourceManager.SourceState.CONNECTED);
                assertThat(status.capabilityVersions()).containsKey("mcp.alpha.lookup");
            });

            restarted.remove("alpha");
        }

        try (CapabilityRuntimeFacade thirdRuntime = new CapabilityRuntimeFacade(8)) {
            McpSourceManager removed = manager(thirdRuntime, adapter, store);
            removed.loadConfiguredSources();

            assertThat(removed.statuses()).isEmpty();
            assertThat(store.configurations()).isEmpty();
        }
    }

    @Test
    void failedReplacementRestoresLastDurableConfiguration() {
        InMemoryMcpSourceStore store = new InMemoryMcpSourceStore();
        FakeAdapter adapter = new FakeAdapter();
        adapter.tools = List.of(tool("lookup", true, "string"));

        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(8)) {
            McpSourceManager manager = new McpSourceManager(
                    runtime,
                    (configuration, authorization) -> {
                        if ("broken.example".equals(configuration.endpoint().getHost())) {
                            throw new IllegalStateException("unreachable");
                        }
                        return adapter;
                    },
                    new ObjectMapper(),
                    name -> "Bearer hidden",
                    store,
                    "");
            manager.register(configuration());
            McpSourceManager.SourceConfiguration broken =
                    new McpSourceManager.SourceConfiguration(
                            "alpha", URI.create("https://broken.example/rpc"), 3,
                            "MCP_ALPHA_TOKEN", Map.of(), false);

            assertThatThrownBy(() -> manager.register(broken))
                    .isInstanceOf(McpSourceManager.McpSourceException.class)
                    .hasMessage("MCP source registration failed closed");

            assertThat(store.find("alpha")).contains(configuration());
            assertThat(runtime.freeze(context("after-failure", "mcp.alpha.lookup"))
                    .exposes("mcp.alpha.lookup")).isTrue();
        }
    }

    private static McpSourceManager manager(
            CapabilityRuntimeFacade runtime, FakeAdapter adapter) {
        return new McpSourceManager(runtime, (configuration, authorization) -> adapter,
                new ObjectMapper(), name -> "Bearer hidden", "");
    }

    private static McpSourceManager manager(
            CapabilityRuntimeFacade runtime,
            FakeAdapter adapter,
            McpSourceStore store) {
        return new McpSourceManager(runtime, (configuration, authorization) -> adapter,
                new ObjectMapper(), name -> "Bearer hidden", store, "");
    }

    private static McpSourceManager.SourceConfiguration configuration() {
        return new McpSourceManager.SourceConfiguration(
                "alpha", URI.create("https://mcp.example/rpc"), 3,
                "MCP_ALPHA_TOKEN", Map.of("X-Tenant", "meguri"), false);
    }

    private static ExposurePlanner.ExposureContext context(
            String turnId, String capabilityId) {
        return new ExposurePlanner.ExposureContext(
                turnId, "tenant", "user", "client", Set.of("mcp:alpha"),
                CapabilityDescriptor.Mode.BALANCED, Set.of(capabilityId), 3,
                true, CapabilityDescriptor.DataClassification.RESTRICTED);
    }

    private static Map<String, Object> tool(
            String name, boolean readOnly, String outputType) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("name", name);
        value.put("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query"),
                "additionalProperties", false));
        value.put("outputSchema", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("type", outputType)),
                "required", List.of("value"),
                "additionalProperties", false));
        value.put("annotations", Map.of("readOnlyHint", readOnly));
        return value;
    }

    private static final class FakeAdapter implements McpAdapter {
        private List<Map<String, Object>> tools = new ArrayList<>();
        private Runnable listener;
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Negotiation negotiate(int maximumProtocol, Set<String> capabilities) {
            return new Negotiation(maximumProtocol, Set.of("tools", "list_changed"));
        }

        @Override
        public List<Map<String, Object>> listTools() {
            return tools;
        }

        @Override
        public Map<String, Object> invoke(
                String toolName,
                Map<String, Object> input,
                CapabilityImplementation.ExecutionContext context) {
            invocations.incrementAndGet();
            return Map.of("value", "ok");
        }

        @Override
        public void onListChanged(Runnable listener) {
            this.listener = listener;
        }
    }
}
