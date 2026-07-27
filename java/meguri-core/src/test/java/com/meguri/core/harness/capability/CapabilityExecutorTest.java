package com.meguri.core.harness.capability;

import com.meguri.core.harness.retrieval.RetrievalMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityExecutorTest {
    @Test
    void enforcesSchemaAndWritesContentFreeReceipt() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("lore.read", new CapabilityInputSchema(
                Map.of("query", CapabilityInputSchema.ValueKind.STRING), Set.of("query"), false));
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "lore.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(1), 1, "test"));
        CapabilityPolicy.ExecutionRequest request = request(
                "turn-a", false, Map.of("query", "hello"), null);

        StepVerifier.create(executor.execute(snapshot, "lore.read", request, () -> Mono.just("result")))
                .expectNext("result")
                .verifyComplete();

        assertThat(ledger.receipts("turn-a")).singleElement().satisfies(receipt -> {
            assertThat(receipt.status()).isEqualTo(EffectLedger.Status.COMPLETED);
            assertThat(receipt.principalId()).isEqualTo("user");
            assertThat(receipt.capabilityVersion()).isEqualTo("1");
            assertThat(receipt.approvalGranted()).isFalse();
            assertThat(receipt.inputHash()).hasSize(64);
            assertThat(receipt.outputHash()).hasSize(64);
            assertThat(receipt.toString()).doesNotContain("hello", "result");
        });

        StepVerifier.create(executor.execute(
                        snapshot,
                        "lore.read",
                        request("turn-b", false, Map.of("query", "hello", "secret", "no"), null),
                        () -> Mono.just("never")))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("unknown capability input"))
                .verify();
    }

    @Test
    void riskBasedWriteRequiresApproval() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("memory.write", emptySchema());
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "memory.write", CapabilityRegistry.Kind.WRITE_TOOL, CapabilityRegistry.Effect.WRITE,
                CapabilityRegistry.Approval.RISK_BASED, Duration.ofSeconds(1), 1, "test"));

        StepVerifier.create(executor.execute(
                        snapshot, "memory.write", request("turn-write", false, Map.of(), null),
                        () -> Mono.just("written")))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("approval"))
                .verify();
        assertThat(ledger.receipts("turn-write")).isEmpty();

        StepVerifier.create(executor.execute(
                        snapshot, "memory.write", request("turn-write", true, Map.of(), null),
                        () -> Mono.just("written")))
                .expectNext("written")
                .verifyComplete();
        assertThat(ledger.receipts("turn-write")).singleElement()
                .extracting(EffectLedger.Receipt::status)
                .isEqualTo(EffectLedger.Status.COMPLETED);
    }

    @Test
    void riskBasedExternalCapabilityAlsoRequiresApproval() {
        CapabilityRegistry.Descriptor descriptor = new CapabilityRegistry.Descriptor(
                "external.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.EXTERNAL,
                CapabilityRegistry.Approval.RISK_BASED, Duration.ofSeconds(1), 1, "test");

        assertThat(new DefaultCapabilityPolicy().authorize(
                descriptor, request("turn-external", false, Map.of(), null)).allowed()).isFalse();
    }

    @Test
    void duplicateStartedIdempotencyScopeCannotExecuteTwice() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("memory.write", emptySchema());
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "memory.write", CapabilityRegistry.Kind.WRITE_TOOL, CapabilityRegistry.Effect.WRITE,
                CapabilityRegistry.Approval.RISK_BASED, Duration.ofSeconds(1), 2, "test"));
        CapabilityPolicy.ExecutionRequest request = request("turn-duplicate", true, Map.of(), null);
        AtomicInteger invocations = new AtomicInteger();
        reactor.core.Disposable first = executor.execute(
                        snapshot, "memory.write", request,
                        () -> Mono.<String>never().doOnSubscribe(ignored -> invocations.incrementAndGet()))
                .subscribe();

        StepVerifier.create(executor.execute(
                        snapshot, "memory.write", request,
                        () -> Mono.fromSupplier(() -> {
                            invocations.incrementAndGet();
                            return "duplicate";
                        })))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("status started"))
                .verify();
        first.dispose();

        assertThat(invocations).hasValue(1);
        assertThat(ledger.receipts("turn-duplicate")).singleElement()
                .extracting(EffectLedger.Receipt::status, EffectLedger.Receipt::errorCode)
                .containsExactly(EffectLedger.Status.FAILED, "cancelled");
    }

    @Test
    void requestDigestIsCanonicalAndPreservesJsonTypes() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("tool.read", emptySchema(true));
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "tool.read", "2", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(1), 2, "test"));
        Map<String, Object> nestedFirst = new LinkedHashMap<>();
        nestedFirst.put("b", List.of(2, 3));
        nestedFirst.put("a", 1);
        Map<String, Object> nestedSecond = new LinkedHashMap<>();
        nestedSecond.put("a", 1.0);
        nestedSecond.put("b", List.of(2L, 3.0));

        executor.execute(snapshot, "tool.read",
                request("turn-canonical-a", false, Map.of("payload", nestedFirst), null),
                () -> Mono.just("first")).block();
        executor.execute(snapshot, "tool.read",
                request("turn-canonical-b", false, Map.of("payload", nestedSecond), null),
                () -> Mono.just("second")).block();

        assertThat(ledger.receipts("turn-canonical-a").getFirst().inputHash())
                .isEqualTo(ledger.receipts("turn-canonical-b").getFirst().inputHash());

        executor.execute(snapshot, "tool.read",
                request("turn-type", false, Map.of("payload", "1"), null),
                () -> Mono.just("string")).block();
        StepVerifier.create(executor.execute(snapshot, "tool.read",
                        request("turn-type", false, Map.of("payload", 1), null),
                        () -> Mono.just("number")))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("payload conflict"))
                .verify();
    }

    @Test
    void effectLedgerTerminalStatesAreMonotonic() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        EffectLedger.Receipt failed = ledger.begin(
                "turn-failed", "user", "tool.write", "1", "operation-failed",
                CapabilityRegistry.Effect.WRITE, true, "input").receipt();
        ledger.fail(failed.receiptId(), "cancelled");
        assertThat(ledger.complete(failed.receiptId(), "late-output").status())
                .isEqualTo(EffectLedger.Status.FAILED);

        EffectLedger.Receipt completed = ledger.begin(
                "turn-completed", "user", "tool.write", "1", "operation-completed",
                CapabilityRegistry.Effect.WRITE, true, "input").receipt();
        ledger.complete(completed.receiptId(), "output");
        assertThat(ledger.fail(completed.receiptId(), "late-error").status())
                .isEqualTo(EffectLedger.Status.COMPLETED);
    }

    @Test
    void idempotencyScopePreventsCrossTurnReplayForTheSamePrincipal() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("external.write", emptySchema(true));
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "external.write", CapabilityRegistry.Kind.WRITE_TOOL, CapabilityRegistry.Effect.WRITE,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(1), 2, "test"));
        AtomicInteger invocations = new AtomicInteger();
        CapabilityPolicy.ExecutionRequest first = new CapabilityPolicy.ExecutionRequest(
                "turn-one", "user", "business-operation", false, Map.of("value", 1), null);
        CapabilityPolicy.ExecutionRequest replay = new CapabilityPolicy.ExecutionRequest(
                "turn-two", "user", "business-operation", false, Map.of("value", 1), null);

        executor.execute(snapshot, "external.write", first,
                () -> Mono.fromSupplier(invocations::incrementAndGet)).block();
        StepVerifier.create(executor.execute(snapshot, "external.write", replay,
                        () -> Mono.fromSupplier(invocations::incrementAndGet)))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("status completed"))
                .verify();

        assertThat(invocations).hasValue(1);
    }

    @Test
    void nonToolKindsAndMissingSchemasCannotReachAnOperation() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        AtomicInteger invocations = new AtomicInteger();
        CapabilityRegistry.Snapshot skill = snapshot(new CapabilityRegistry.Descriptor(
                "persona.prompt", CapabilityRegistry.Kind.PROMPT_SKILL, CapabilityRegistry.Effect.NONE,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(1), 1, "prompt"));

        StepVerifier.create(executor.execute(skill, "persona.prompt",
                        request("turn-skill", false, Map.of(), null),
                        () -> Mono.fromSupplier(invocations::incrementAndGet)))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("not executable"))
                .verify();

        CapabilityRegistry.Snapshot tool = snapshot(new CapabilityRegistry.Descriptor(
                "tool.missing-schema", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(1), 1, "tool"));
        StepVerifier.create(executor.execute(tool, "tool.missing-schema",
                        request("turn-schema", false, Map.of(), null),
                        () -> Mono.fromSupplier(invocations::incrementAndGet)))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("schema is not registered"))
                .verify();

        assertThat(invocations).hasValue(0);
        assertThat(ledger.receipts("turn-skill")).isEmpty();
        assertThat(ledger.receipts("turn-schema")).isEmpty();
    }

    @Test
    void registrySwitchesSkillVersionsWithoutMutatingFrozenSnapshots() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilityRegistry.Descriptor versionOne = new CapabilityRegistry.Descriptor(
                "persona.prompt", "1.0.0", CapabilityRegistry.Kind.PROMPT_SKILL,
                CapabilityRegistry.Effect.NONE, CapabilityRegistry.Approval.NONE,
                Duration.ofSeconds(1), 1, "prompt-v1");
        CapabilityRegistry.Descriptor versionTwo = new CapabilityRegistry.Descriptor(
                "persona.prompt", "2.0.0", CapabilityRegistry.Kind.PROMPT_SKILL,
                CapabilityRegistry.Effect.NONE, CapabilityRegistry.Approval.NONE,
                Duration.ofSeconds(1), 1, "prompt-v2");
        registry.register(versionOne);
        CapabilityRegistry.Snapshot frozen = registry.freeze();
        registry.register(versionTwo);

        assertThat(registry.freeze().grants()).containsExactly(versionOne);
        registry.activate("persona.prompt", "2.0.0");
        assertThat(registry.freeze().grants()).containsExactly(versionTwo);
        assertThat(frozen.grants()).containsExactly(versionOne);

        registry.disable("persona.prompt");
        assertThat(registry.freeze().grants()).isEmpty();
    }

    @Test
    void remoteAgentRequiresExplicitBoundedSandbox() {
        DefaultCapabilityPolicy policy = new DefaultCapabilityPolicy();
        CapabilityRegistry.Descriptor descriptor = new CapabilityRegistry.Descriptor(
                "agent.run", CapabilityRegistry.Kind.REMOTE_AGENT, CapabilityRegistry.Effect.EXTERNAL,
                CapabilityRegistry.Approval.ALWAYS, Duration.ofSeconds(10), 1, "test-agent");

        assertThat(policy.authorize(descriptor, request("turn-agent", true, Map.of(), null)).allowed())
                .isFalse();
        CapabilityPolicy.SandboxBudget sandbox = new CapabilityPolicy.SandboxBudget(
                Duration.ofSeconds(5), 3, 4, 100,
                Set.of("lore.read", "web.read"),
                Set.of("D:/program/meguri-pet"), Set.of(), false);
        assertThat(policy.authorize(
                descriptor, request("turn-agent", true, Map.of(), sandbox, RetrievalMode.SLOW)).allowed()).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CapabilityPolicy.SandboxBudget(
                        Duration.ofSeconds(5), 3, 4, 0,
                        Set.of("lore.read"), Set.of("D:/program/meguri-pet"), Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCostUnits");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CapabilityPolicy.SandboxBudget(
                        Duration.ofSeconds(5), 3, 4, 100,
                        Set.of(), Set.of("D:/program/meguri-pet"), Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedCapabilities");
    }

    @Test
    void fastRejectsRemoteAgentBeforeOperationEvenWithForgedGrantAndInput() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("agent.run", emptySchema(true));
        CapabilityRegistry.Snapshot forgedGrant = snapshot(new CapabilityRegistry.Descriptor(
                "agent.run", CapabilityRegistry.Kind.REMOTE_AGENT, CapabilityRegistry.Effect.EXTERNAL,
                CapabilityRegistry.Approval.ALWAYS, Duration.ofSeconds(10), 1, "test-agent"));
        CapabilityPolicy.SandboxBudget sandbox = new CapabilityPolicy.SandboxBudget(
                Duration.ofSeconds(5), 3, 4, 100,
                Set.of("agent.run"), Set.of("D:/program/meguri-pet"), Set.of(), false);
        CapabilityPolicy.ExecutionRequest forgedRequest = request(
                "turn-fast-agent", true, Map.of("retrieval_mode", "SLOW"), sandbox, RetrievalMode.FAST);
        AtomicInteger invocations = new AtomicInteger();

        StepVerifier.create(executor.execute(
                        forgedGrant, "agent.run", forgedRequest,
                        () -> Mono.fromSupplier(invocations::incrementAndGet)))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("FAST"))
                .verify();

        assertThat(invocations).hasValue(0);
        assertThat(ledger.receipts("turn-fast-agent")).isEmpty();
    }

    @Test
    void noneRejectsReadRetrievalBeforeOperationEvenWithForgedInput() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("lore.read", emptySchema(true));
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "lore.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(1), 1, "test"));
        CapabilityPolicy.ExecutionRequest forgedRequest = request(
                "turn-none", false, Map.of("retrieval_mode", "SLOW"), null, RetrievalMode.NONE);
        AtomicInteger invocations = new AtomicInteger();

        StepVerifier.create(executor.execute(
                        snapshot, "lore.read", forgedRequest,
                        () -> Mono.fromSupplier(invocations::incrementAndGet)))
                .expectErrorMatches(error -> error instanceof CapabilityExecutionException
                        && error.getMessage().contains("NONE"))
                .verify();

        assertThat(invocations).hasValue(0);
        assertThat(ledger.receipts("turn-none")).isEmpty();
    }

    @Test
    void canonicalInputDigestChangesWhenNestedCandidateContentChanges() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        CapabilityExecutor executor = new CapabilityExecutor(new DefaultCapabilityPolicy(), ledger);
        executor.registerSchema("memory.write", new CapabilityInputSchema(
                Map.of("candidates", CapabilityInputSchema.ValueKind.ARRAY),
                Set.of("candidates"), false));
        CapabilityRegistry.Snapshot snapshot = snapshot(new CapabilityRegistry.Descriptor(
                "memory.write", CapabilityRegistry.Kind.WRITE_TOOL, CapabilityRegistry.Effect.WRITE,
                CapabilityRegistry.Approval.ALWAYS, Duration.ofSeconds(1), 1, "test"));
        Map<String, Object> first = Map.of("candidates", List.of(Map.of(
                "type", "preference", "summary", "likes tea")));
        Map<String, Object> second = Map.of("candidates", List.of(Map.of(
                "type", "preference", "summary", "likes coffee")));

        StepVerifier.create(executor.execute(
                        snapshot, "memory.write", request("turn-a", true, first, null),
                        () -> Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();
        StepVerifier.create(executor.execute(
                        snapshot, "memory.write", request("turn-b", true, second, null),
                        () -> Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();

        assertThat(ledger.receipts("turn-a").getFirst().inputHash())
                .isNotEqualTo(ledger.receipts("turn-b").getFirst().inputHash());
    }

    @Test
    void normalizesMcpAnnotationsAndInputSchema() {
        McpCapabilityNormalizer.Normalized normalized = McpCapabilityNormalizer.normalize(
                "files",
                Map.of(
                        "name", "delete_file",
                        "annotations", Map.of("destructiveHint", true, "readOnlyHint", false),
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("path", Map.of("type", "string")),
                                "required", List.of("path"),
                                "additionalProperties", false)));

        assertThat(normalized.descriptor().id()).isEqualTo("mcp.files.delete_file");
        assertThat(normalized.descriptor().effect()).isEqualTo(CapabilityRegistry.Effect.WRITE);
        assertThat(normalized.descriptor().approval()).isEqualTo(CapabilityRegistry.Approval.ALWAYS);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> normalized.inputSchema().validate(Map.of()))
                .isInstanceOf(CapabilityExecutionException.class)
                .hasMessageContaining("path");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> McpCapabilityNormalizer.normalize(
                        "files",
                        Map.of(
                                "name", "ambiguous",
                                "annotations", Map.of("readOnlyHint", true, "destructiveHint", true),
                                "inputSchema", Map.of("type", "object", "properties", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflict");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> McpCapabilityNormalizer.normalize(
                        "files",
                        Map.of(
                                "name", "complex",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of("path", Map.of(
                                                "type", "string", "pattern", "^[a-z]+$"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported MCP schema keyword");

        McpCapabilityNormalizer.Normalized hintedRead = McpCapabilityNormalizer.normalize(
                "files",
                Map.of(
                        "name", "read_file",
                        "annotations", Map.of("readOnlyHint", true, "openWorldHint", false),
                        "inputSchema", Map.of("type", "object", "properties", Map.of())));
        assertThat(hintedRead.descriptor().effect()).isEqualTo(CapabilityRegistry.Effect.EXTERNAL);
        assertThat(hintedRead.descriptor().kind()).isEqualTo(CapabilityRegistry.Kind.WRITE_TOOL);
        assertThat(hintedRead.descriptor().approval())
                .as("untrusted MCP annotations must not waive local approval")
                .isEqualTo(CapabilityRegistry.Approval.RISK_BASED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> McpCapabilityNormalizer.normalize(
                        "files",
                        Map.of(
                                "name", "malformed_hint",
                                "annotations", Map.of("readOnlyHint", "true"),
                                "inputSchema", Map.of("type", "object", "properties", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be boolean");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> McpCapabilityNormalizer.normalize(
                        "files",
                        Map.of(
                                "name", "unbounded",
                                "timeout_ms", 99,
                                "inputSchema", Map.of("type", "object", "properties", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout_ms");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> McpCapabilityNormalizer.normalize(
                        "files",
                        Map.of(
                                "name", "malformed_required",
                                "inputSchema", Map.of(
                                        "type", "object", "properties", Map.of(), "required", "path"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required must be an array");
    }

    private static CapabilityRegistry.Snapshot snapshot(CapabilityRegistry.Descriptor descriptor) {
        return new CapabilityRegistry.Snapshot("test", Instant.now(), List.of(descriptor));
    }

    private static CapabilityInputSchema emptySchema() {
        return emptySchema(false);
    }

    private static CapabilityInputSchema emptySchema(boolean additionalProperties) {
        return new CapabilityInputSchema(Map.of(), Set.of(), additionalProperties);
    }

    private static CapabilityPolicy.ExecutionRequest request(
            String turnId,
            boolean approved,
            Map<String, Object> input,
            CapabilityPolicy.SandboxBudget sandbox) {
        return new CapabilityPolicy.ExecutionRequest(
                turnId, "user", turnId + ":operation", approved, input, sandbox);
    }

    private static CapabilityPolicy.ExecutionRequest request(
            String turnId,
            boolean approved,
            Map<String, Object> input,
            CapabilityPolicy.SandboxBudget sandbox,
            RetrievalMode retrievalMode) {
        return new CapabilityPolicy.ExecutionRequest(
                turnId, "user", turnId + ":operation", approved, input, sandbox, retrievalMode);
    }
}
