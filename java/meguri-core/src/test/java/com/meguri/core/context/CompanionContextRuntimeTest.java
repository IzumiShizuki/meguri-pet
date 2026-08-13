package com.meguri.core.context;

import com.meguri.core.llm.ProviderTokenizer;
import com.meguri.core.runtime.SessionContextStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionContextRuntimeTest {
    private final WordTokenizer tokenizer = new WordTokenizer();

    @Test
    void activeBranchNeverContainsSiblingMessagesAndStaleSummaryIsExcluded() {
        SessionContextStore store = new SessionContextStore(20);
        SessionContextStore.MessageNode root = append(store, null, "user", "root question");
        SessionContextStore.MessageNode sibling = append(store, null, "assistant", "sibling secret");
        store.addSummary("u", "web", "c", List.of(sibling.messageId()), "stale sibling summary", "r1");
        SessionContextStore.MessageNode selected = append(store, root.messageId(), "assistant", "selected answer");
        InMemoryContextRuntimePersistence persistence = new InMemoryContextRuntimePersistence();

        ContextBundle bundle = runtime(store, persistence).build(request(profile(200, 180), List.of())).bundle();

        assertThat(bundle.activeLeafMessageId()).isEqualTo(selected.messageId());
        assertThat(bundle.blocks()).extracting(ContextBundle.Block::content)
                .contains("user: root question", "assistant: selected answer")
                .noneMatch(content -> content.contains("sibling"));
        assertThat(store.graph("u", "web", "c").summaries().getFirst().status())
                .isEqualTo(SessionContextStore.SummaryStatus.STALE);
    }

    @Test
    void quoteRestoresBoundedWindowAndPreservesExactSnapshot() {
        SessionContextStore store = new SessionContextStore(20);
        SessionContextStore.MessageNode source = null;
        for (int i = 0; i < 10; i++) {
            SessionContextStore.MessageNode node = append(store, null, i % 2 == 0 ? "user" : "assistant",
                    "message " + i + " important phrase");
            if (i == 4) source = node;
        }
        String leaf = store.graph("u", "web", "c").activeLeafMessageId();
        SessionContextStore.ContextReference quote = store.quote(
                "u", "web", "c", source.messageId(), leaf, 10, 19);
        String sourceId = source.messageId();

        ContextBundle bundle = runtime(store, new InMemoryContextRuntimePersistence())
                .build(request(profile(400, 350), List.of())).bundle();

        List<ContextBundle.Block> restored = bundle.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.REHYDRATED)
                .toList();
        assertThat(restored).hasSize(2).allMatch(block ->
                block.sourceIds().equals(List.of(quote.referenceId(), sourceId)));
        assertThat(restored).extracting(ContextBundle.Block::content)
                .anyMatch(content -> content.contains("exact_reference: important"))
                .anyMatch(content -> content.contains("message 0") && content.contains("message 8")
                        && !content.contains("message 9 important phrase"));
    }

    @Test
    void rehydrationSkipsSiblingSourceAndFollowsOnlyTheActiveAncestorPath() {
        SessionContextStore store = new SessionContextStore(20);
        SessionContextStore.MessageNode root = append(store, null, "user", "root");
        SessionContextStore.MessageNode sibling = append(
                store, root.messageId(), "assistant", "sibling secret");
        SessionContextStore.MessageNode siblingLeaf = append(
                store, sibling.messageId(), "user", "sibling continuation");
        SessionContextStore.ContextReference siblingQuote = store.quote(
                "u", "web", "c", sibling.messageId(), siblingLeaf.messageId(), 0, 7);

        SessionContextStore.MessageNode active = store.resumeFrom(
                "u", "web", "c", root.messageId(),
                new SessionContextStore.Message("assistant", "active answer"));
        SessionContextStore.MessageNode activeLeaf = append(
                store, active.messageId(), "user", "active continuation");
        SessionContextStore.ContextReference activeQuote = store.topicLink(
                "u", "web", "c", root.messageId(), activeLeaf.messageId());

        ContextBundle bundle = runtime(store, new InMemoryContextRuntimePersistence())
                .build(request(profile(400, 350), List.of())).bundle();

        List<ContextBundle.Block> restored = bundle.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.REHYDRATED)
                .toList();
        assertThat(restored)
                .noneMatch(block -> block.sourceIds().contains(siblingQuote.referenceId()))
                .anyMatch(block -> block.sourceIds().contains(activeQuote.referenceId())
                        && block.content().contains("active answer")
                        && block.content().contains("active continuation")
                        && !block.content().contains("sibling secret"));
    }

    @Test
    void sourceAndHardBudgetsRecordDeterministicTruncationReasons() {
        SessionContextStore store = new SessionContextStore(20);
        append(store, null, "user", "current input cannot disappear");
        List<ContextBuildRequest.ExternalBlock> tools = List.of(
                tool("t1", words("one", 25)), tool("t2", words("two", 25)),
                tool("t3", words("three", 25)), tool("t4", words("four", 25)));

        ContextBundle bundle = runtime(store, new InMemoryContextRuntimePersistence())
                .build(request(profile(120, 90), tools)).bundle();

        assertThat(bundle.budget().consumedTokens()).isLessThanOrEqualTo(bundle.budget().hardThresholdTokens());
        assertThat(bundle.blocks()).anyMatch(block -> block.content().contains("current input"));
        assertThat(bundle.truncations()).isNotEmpty()
                .allMatch(item -> item.reason().equals("source_max_exceeded")
                        || item.reason().equals("hard_threshold_low_priority_block"));
        assertThat(bundle.truncations()).extracting(ContextBundle.Truncation::sourceIds).contains(List.of("t1"));
    }

    @Test
    void traceReplaysExactBundleWithoutRebuilding() {
        SessionContextStore store = new SessionContextStore(20);
        append(store, null, "user", "remember this turn");
        InMemoryContextRuntimePersistence persistence = new InMemoryContextRuntimePersistence();
        CompanionContextRuntime runtime = runtime(store, persistence);

        CompanionContextRuntime.BuildResult built = runtime.build(request(profile(200, 180), List.of()));

        assertThat(runtime.replay(built.traceId())).isEqualTo(built.bundle());
        append(store, null, "assistant", "later mutation");
        assertThat(runtime.replay(built.traceId())).isEqualTo(built.bundle());
    }

    @Test
    void softThresholdJobIsIdempotentAndExpiredLeaseCanRecover() {
        SessionContextStore store = new SessionContextStore(20);
        append(store, null, "user", words("history", 100));
        InMemoryContextRuntimePersistence persistence = new InMemoryContextRuntimePersistence();
        CompanionContextRuntime runtime = runtime(store, persistence);
        ContextBuildRequest request = request(profile(120, 110), List.of());

        ContextRuntimePersistence.PrecompressionJob first = runtime.build(request).precompressionJob().orElseThrow();
        ContextRuntimePersistence.PrecompressionJob duplicate = runtime.build(request).precompressionJob().orElseThrow();

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(persistence.recoverablePrecompressionJobs(Instant.now().plusSeconds(1)))
                .extracting(ContextRuntimePersistence.PrecompressionJob::jobId)
                .containsExactly(first.jobId());
        assertThat(persistence.claimPrecompression(
                first.jobId(), "worker-a", "claim-a", Instant.now().minusSeconds(1))).isPresent();
        assertThat(persistence.heartbeatPrecompression(
                first.jobId(), "worker-a", "claim-a", Instant.now().plusSeconds(30))).isFalse();
        assertThat(persistence.claimPrecompression(
                first.jobId(), "worker-b", "claim-b", Instant.now().plusSeconds(30)))
                .get().extracting(ContextRuntimePersistence.PrecompressionJob::attempts).isEqualTo(2);
        assertThat(persistence.claimPrecompression(
                first.jobId(), "worker-c", "claim-c", Instant.now().plusSeconds(30))).isEmpty();
        assertThat(persistence.completePrecompression(
                first.jobId(), "worker-a", "claim-a", "stale-summary")).isFalse();
        assertThat(persistence.retryPrecompression(
                first.jobId(), "worker-a", "claim-a", Instant.now(), false)).isFalse();
        assertThat(persistence.heartbeatPrecompression(
                first.jobId(), "worker-a", "claim-a", Instant.now().plusSeconds(60))).isFalse();
        assertThat(persistence.completePrecompression(
                first.jobId(), "worker-b", "claim-b", "fresh-summary")).isTrue();
    }

    @Test
    void failedPrecompressionJobIsTerminalLikePostgres() {
        InMemoryContextRuntimePersistence persistence = new InMemoryContextRuntimePersistence();
        Instant now = Instant.now();
        ContextRuntimePersistence.PrecompressionJob job = persistence.enqueuePrecompression(
                new ContextRuntimePersistence.PrecompressionJob(
                        "failed-job", "failed-key", "u", "web", "c", 0, List.of("m"),
                        "model", ContextRuntimePersistence.JobStatus.PENDING, 0,
                        now, null, null, now));
        persistence.claimPrecompression(job.jobId(), "worker", "token", now.plusSeconds(30));
        assertThat(persistence.retryPrecompression(
                job.jobId(), "worker", "token", now, true)).isTrue();

        assertThat(persistence.recoverablePrecompressionJobs(now.plusSeconds(60))).isEmpty();
        assertThat(persistence.claimPrecompression(
                job.jobId(), "other", "other-token", now.plusSeconds(90))).isEmpty();
    }

    @Test
    void resumeFromCreatesRealBranchWhileQuoteDoesNotMoveLeaf() {
        SessionContextStore store = new SessionContextStore(20);
        SessionContextStore.MessageNode root = append(store, null, "user", "root");
        append(store, null, "assistant", "old branch");
        String beforeQuote = store.graph("u", "web", "c").activeLeafMessageId();
        store.quote("u", "web", "c", root.messageId(), beforeQuote, 0, 4);
        assertThat(store.graph("u", "web", "c").activeLeafMessageId()).isEqualTo(beforeQuote);

        SessionContextStore.MessageNode resumed = store.resumeFrom(
                "u", "web", "c", root.messageId(), new SessionContextStore.Message("assistant", "new branch"));

        assertThat(store.graph("u", "web", "c").activeLeafMessageId()).isEqualTo(resumed.messageId());
        assertThat(store.graph("u", "web", "c").activePath())
                .extracting(SessionContextStore.MessageNode::content).containsExactly("root", "new branch");
    }

    @Test
    void thinkSelectivelyRehydratesRelevantStructuredFactsOnActivePath() {
        SessionContextStore store = new SessionContextStore(30);
        SessionContextStore.MessageNode constraint = append(store, null, "user",
                "The database constraint is postgres and must stay stable");
        SessionContextStore.MessageNode decision = append(store, null, "assistant",
                "We chose postgres because the existing migration path is compatible");
        StructuredContextSummary structured = StructuredContextSummary.builder("postgres decision")
                .fact(new StructuredContextSummary.Fact(
                        "fact-postgres", "postgres was chosen for the migration path",
                        List.of(decision.messageId()), StructuredContextSummary.Importance.HIGH,
                        StructuredContextSummary.FactStatus.ACTIVE, List.of()))
                .fact(new StructuredContextSummary.Fact(
                        "fact-unrelated", "the mascot color is blue",
                        List.of(constraint.messageId()), StructuredContextSummary.Importance.NORMAL,
                        StructuredContextSummary.FactStatus.ACTIVE, List.of()))
                .currentState("fact-postgres")
                .operation(new StructuredContextSummary.Operation(
                        StructuredContextSummary.OperationType.COMPRESS,
                        List.of("fact-postgres"), List.of(decision.messageId()), "fact-postgres"))
                .operation(new StructuredContextSummary.Operation(
                        StructuredContextSummary.OperationType.COMPRESS,
                        List.of("fact-unrelated"), List.of(constraint.messageId()), "fact-unrelated"))
                .build();
        store.addSummary("u", "web", "c", List.of(constraint.messageId(), decision.messageId()),
                "postgres decision", "test-structured", structured);
        SessionContextStore.MessageNode question = append(store, null, "user",
                "Why did we choose postgres for the migration path?");

        CompanionContextRuntime runtime = new CompanionContextRuntime(
                store, new InMemoryContextRuntimePersistence(), tokenizer,
                new DeterministicTopicDetector(), true, "test-strategy");
        ContextBundle bundle = runtime.build(new ContextBuildRequest(
                "u", "web", "c", profile(500, 450), "general", 0.2, List.of(),
                question.content(), new TopicSignal("database", 0.8, "test", "test-detector"),
                RehydrationPolicy.thinkDefault())).bundle();

        assertThat(bundle.blocks()).anyMatch(block ->
                block.blockType() == ContextBundle.BlockType.REHYDRATED
                        && block.sourceIds().stream().anyMatch("auto-fact:fact-postgres"::equals)
                        && block.content().contains("We chose postgres"));
        assertThat(bundle.blocks()).noneMatch(block ->
                block.sourceIds().stream().anyMatch("auto-fact:fact-unrelated"::equals));
        assertThat(bundle.rehydrationDecisions()).anyMatch(decisionResult ->
                decisionResult.factId().equals("fact-postgres") && decisionResult.selected());
        assertThat(bundle.rehydrationDecisions()).noneMatch(decisionResult ->
                decisionResult.factId().equals("fact-unrelated"));
    }

    @Test
    void disabledRehydrationDoesNotRestoreStructuredFacts() {
        SessionContextStore store = new SessionContextStore(20);
        SessionContextStore.MessageNode source = append(store, null, "user", "the exact route is /v1/items");
        StructuredContextSummary structured = StructuredContextSummary.builder("route")
                .fact(new StructuredContextSummary.Fact(
                        "fact-route", "the exact route is /v1/items", List.of(source.messageId()),
                        StructuredContextSummary.Importance.HIGH,
                        StructuredContextSummary.FactStatus.ACTIVE, List.of()))
                .exactItem("fact-route")
                .operation(new StructuredContextSummary.Operation(
                        StructuredContextSummary.OperationType.KEEP_EXACT,
                        List.of("fact-route"), List.of(source.messageId()), "fact-route"))
                .build();
        store.addSummary("u", "web", "c", List.of(source.messageId()), "route", "test", structured);
        SessionContextStore.MessageNode question = append(store, null, "user", "what is the exact route?");

        ContextBundle bundle = new CompanionContextRuntime(
                store, new InMemoryContextRuntimePersistence(), tokenizer,
                new DeterministicTopicDetector(), true, "test")
                .build(new ContextBuildRequest(
                        "u", "web", "c", profile(300, 250), "general", 0.2, List.of(),
                        question.content(), null, RehydrationPolicy.disabled()))
                .bundle();

        assertThat(bundle.blocks()).noneMatch(block -> block.blockType() == ContextBundle.BlockType.REHYDRATED
                && block.sourceIds().stream().anyMatch(value -> value.startsWith("auto-fact:")));
    }

    @Test
    void invalidReadModelFallsBackToHarnessAndRequestsRebuild() {
        SessionContextStore store = new SessionContextStore(20);
        append(store, null, "user", "read model fallback");
        AtomicInteger rebuilds = new AtomicInteger();
        ConversationContextReadModelProvider provider = new ConversationContextReadModelProvider() {
            @Override
            public Optional<ConversationContextReadModel> find(ContextBuildRequest request,
                                                               SessionContextStore.GraphSnapshot authority) {
                return Optional.of(new ConversationContextReadModel(
                        authority.sessionId(), authority.activeLeafMessageId(), null, null,
                        List.of(), authority.activePath(), List.of(), List.of(), Map.of(), Map.of(),
                        0, authority.revision() - 1, "bad-digest", "test"));
            }

            @Override
            public void rebuild(ContextBuildRequest request,
                                SessionContextStore.GraphSnapshot authority) {
                rebuilds.incrementAndGet();
            }
        };

        ContextBundle bundle = new CompanionContextRuntime(
                store, new InMemoryContextRuntimePersistence(), tokenizer,
                new DeterministicTopicDetector(), false, true, "test", provider)
                .build(request(profile(200, 180), List.of())).bundle();

        assertThat(bundle.blocks()).anyMatch(block -> block.content().contains("read model fallback"));
        assertThat(rebuilds).hasValue(1);
    }

    private CompanionContextRuntime runtime(SessionContextStore store, ContextRuntimePersistence persistence) {
        return new CompanionContextRuntime(store, persistence, tokenizer);
    }

    private ContextBuildRequest request(ContextProfile profile, List<ContextBuildRequest.ExternalBlock> blocks) {
        return new ContextBuildRequest("u", "web", "c", profile, "general", 0.2, blocks);
    }

    private ContextProfile profile(int usable, int toolMax) {
        EnumMap<ContextBundle.BlockType, ContextProfile.SourceBudget> budgets =
                new EnumMap<>(ContextBundle.BlockType.class);
        for (ContextBundle.BlockType type : ContextBundle.BlockType.values()) {
            budgets.put(type, new ContextProfile.SourceBudget(0, usable));
        }
        budgets.put(ContextBundle.BlockType.TOOL_RESULT, new ContextProfile.SourceBudget(0, toolMax));
        return new ContextProfile("test-model", usable + 20, 10, 10, 0.70, 0.90, budgets);
    }

    private SessionContextStore.MessageNode append(SessionContextStore store, String parent, String role, String text) {
        return store.appendNode("u", "web", "c", parent, new SessionContextStore.Message(role, text));
    }

    private static ContextBuildRequest.ExternalBlock tool(String id, String content) {
        return new ContextBuildRequest.ExternalBlock(ContextBundle.BlockType.TOOL_RESULT,
                List.of(id), ContextBundle.Trust.UNTRUSTED_EXTERNAL, content, false);
    }

    private static String words(String word, int count) {
        return (word + " ").repeat(count).trim();
    }

    private static final class WordTokenizer implements ProviderTokenizer {
        @Override public int count(String text) {
            return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
        }
        @Override public String truncate(String text, int maxTokens) { return text; }
        @Override public String name() { return "test-words"; }
    }
}
