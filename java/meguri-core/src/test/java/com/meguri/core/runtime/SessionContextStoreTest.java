package com.meguri.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SessionContextStoreTest {
    @Test
    void restoresCompleteGraphFromPersistence() {
        RecordingPersistence persistence = new RecordingPersistence();
        SessionContextStore first = new SessionContextStore(8, persistence);
        SessionContextStore.MessageNode user = first.appendNode(
                "user", "website", "durable", null,
                new SessionContextStore.Message("user", "question"));
        SessionContextStore.MessageNode assistant = first.appendNode(
                "user", "website", "durable", null,
                new SessionContextStore.Message("assistant", "answer"));
        first.link("user", "website", "durable", SessionContextStore.ReferenceType.QUOTE,
                user.messageId(), assistant.messageId());
        first.addSummary("user", "website", "durable", List.of(user.messageId(), assistant.messageId()),
                "summary", "model-r1");

        SessionContextStore restored = new SessionContextStore(8, persistence);
        SessionContextStore.GraphSnapshot graph = restored.graph("user", "website", "durable");

        assertThat(graph.activeLeafMessageId()).isEqualTo(assistant.messageId());
        assertThat(graph.allNodes()).containsExactlyElementsOf(
                first.graph("user", "website", "durable").allNodes());
        assertThat(graph.references()).singleElement()
                .extracting(SessionContextStore.ContextReference::type)
                .isEqualTo(SessionContextStore.ReferenceType.QUOTE);
        assertThat(graph.summaries()).singleElement()
                .extracting(SessionContextStore.DerivedSummary::content)
                .isEqualTo("summary");
        assertThat(graph.summaries().getFirst().sourceRevisionDigest()).matches("[0-9a-f]{64}");
        assertThat(graph.summaries().getFirst().status()).isEqualTo(SessionContextStore.SummaryStatus.ACTIVE);
        assertThat(restored.activeSummaries("user", "website", "durable"))
                .containsExactly(graph.summaries().getFirst());
        assertThat(restored.recent("user", "website", "durable"))
                .extracting(SessionContextStore.Message::content)
                .containsExactly("question", "answer");
    }

    @Test
    void summaryCapturesADeterministicDigestAndStartsActive() {
        SessionContextStore store = new SessionContextStore(8);
        SessionContextStore.MessageNode user = store.appendNode(
                "user", "website", "session", null,
                new SessionContextStore.Message("user", "question"));
        SessionContextStore.MessageNode assistant = store.appendNode(
                "user", "website", "session", null,
                new SessionContextStore.Message("assistant", "answer"));

        SessionContextStore.DerivedSummary first = store.addSummary(
                "user", "website", "session", List.of(user.messageId(), assistant.messageId()),
                "summary one", "model-r1");
        SessionContextStore.DerivedSummary second = store.addSummary(
                "user", "website", "session", List.of(user.messageId(), assistant.messageId()),
                "summary two", "model-r2");

        assertThat(first.sourceRevisionDigest()).matches("[0-9a-f]{64}");
        assertThat(second.sourceRevisionDigest()).isEqualTo(first.sourceRevisionDigest());
        assertThat(first.status()).isEqualTo(SessionContextStore.SummaryStatus.ACTIVE);
        assertThat(store.activeSummaries("user", "website", "session")).containsExactly(first, second);
    }

    @Test
    void linksAProvisionalSegmentWithoutDeletingItsRawBranch() {
        SessionContextStore store = new SessionContextStore(8);
        store.append("user", "desktop_pet", "parent", new SessionContextStore.Message("user", "old"));
        store.append("user", "desktop_pet", "candidate", new SessionContextStore.Message("user", "first"));
        store.append("user", "desktop_pet", "candidate", new SessionContextStore.Message("assistant", "answer"));

        store.mergeInto("user", "desktop_pet", "parent", "candidate");

        assertThat(store.recent("user", "desktop_pet", "parent"))
                .extracting(SessionContextStore.Message::content)
                .containsExactly("old", "first", "answer");
        assertThat(store.recent("user", "desktop_pet", "candidate"))
                .extracting(SessionContextStore.Message::content)
                .containsExactly("first", "answer");
        assertThat(store.graph("user", "desktop_pet", "parent").references())
                .extracting(SessionContextStore.ContextReference::type)
                .containsExactly(SessionContextStore.ReferenceType.TOPIC_LINK);
    }

    @Test
    void replacementSelectsANewLeafAndKeepsTheOriginalNode() {
        SessionContextStore store = new SessionContextStore(8);
        SessionContextStore.MessageNode user = store.appendNode(
                "user", "website", "session", null,
                new SessionContextStore.Message("user", "question"));
        SessionContextStore.MessageNode original = store.appendNode(
                "user", "website", "session", null,
                new SessionContextStore.Message("assistant", "candidate-a"));
        SessionContextStore.DerivedSummary originalSummary = store.addSummary(
                "user", "website", "session", List.of(user.messageId(), original.messageId()),
                "candidate-a summary", "model-r1");
        String originalLeaf = store.graph("user", "website", "session").activeLeafMessageId();

        assertThat(store.replaceLastAssistant(
                "user", "website", "session", "candidate-a", "candidate-b")).isTrue();

        SessionContextStore.GraphSnapshot graph = store.graph("user", "website", "session");
        assertThat(graph.activeLeafMessageId()).isNotEqualTo(originalLeaf);
        assertThat(graph.allNodes()).extracting(SessionContextStore.MessageNode::content)
                .containsExactly("question", "candidate-a", "candidate-b");
        assertThat(graph.activePath()).extracting(SessionContextStore.MessageNode::content)
                .containsExactly("question", "candidate-b");
        assertThat(graph.references()).extracting(SessionContextStore.ContextReference::type)
                .containsExactly(SessionContextStore.ReferenceType.RESUME_FROM);
        SessionContextStore.DerivedSummary stale = graph.summaries().getFirst();
        assertThat(stale.status()).isEqualTo(SessionContextStore.SummaryStatus.STALE);
        assertThat(stale.summaryId()).isEqualTo(originalSummary.summaryId());
        assertThat(stale.content()).isEqualTo(originalSummary.content());
        assertThat(stale.sourceRevisionDigest()).isEqualTo(originalSummary.sourceRevisionDigest());
        assertThat(store.activeSummaries("user", "website", "session")).isEmpty();

        store.link("user", "website", "session", SessionContextStore.ReferenceType.QUOTE,
                graph.activePath().getFirst().messageId(), graph.activeLeafMessageId());
        assertThat(store.graph("user", "website", "session").references())
                .extracting(SessionContextStore.ContextReference::type)
                .containsExactly(SessionContextStore.ReferenceType.RESUME_FROM,
                         SessionContextStore.ReferenceType.QUOTE);
    }

    @Test
    void implicitAndExplicitBranchSwitchesStaleOnlySummariesThatLeaveTheActivePath() {
        SessionContextStore store = new SessionContextStore(8);
        SessionContextStore.MessageNode root = store.appendNode(
                "user", "website", "session", null, new SessionContextStore.Message("user", "root"));
        SessionContextStore.MessageNode branchA = store.appendNode(
                "user", "website", "session", null, new SessionContextStore.Message("assistant", "branch-a"));
        SessionContextStore.DerivedSummary rootSummary = store.addSummary(
                "user", "website", "session", List.of(root.messageId()), "root summary", "model-r1");
        SessionContextStore.DerivedSummary branchASummary = store.addSummary(
                "user", "website", "session", List.of(branchA.messageId()), "branch a summary", "model-r1");

        SessionContextStore.MessageNode branchB = store.appendNode(
                "user", "website", "session", root.messageId(),
                new SessionContextStore.Message("assistant", "branch-b"));

        assertThat(store.graph("user", "website", "session").summaries())
                .extracting(SessionContextStore.DerivedSummary::status)
                .containsExactly(SessionContextStore.SummaryStatus.ACTIVE, SessionContextStore.SummaryStatus.STALE);
        assertThat(store.activeSummaries("user", "website", "session")).containsExactly(rootSummary);

        assertThat(store.selectActiveLeaf("user", "website", "session", branchA.messageId())).isTrue();
        SessionContextStore.DerivedSummary regenerated = store.addSummary(
                "user", "website", "session", List.of(branchA.messageId()),
                "branch a summary regenerated", "model-r2");
        assertThat(regenerated.summaryId()).isNotEqualTo(branchASummary.summaryId());
        assertThat(regenerated.sourceRevisionDigest()).isEqualTo(branchASummary.sourceRevisionDigest());
        assertThat(regenerated.status()).isEqualTo(SessionContextStore.SummaryStatus.ACTIVE);

        assertThat(store.selectActiveLeaf("user", "website", "session", branchB.messageId())).isTrue();
        SessionContextStore.GraphSnapshot graph = store.graph("user", "website", "session");
        assertThat(graph.summaries()).hasSize(3);
        assertThat(graph.summaries()).extracting(SessionContextStore.DerivedSummary::status)
                .containsExactly(SessionContextStore.SummaryStatus.ACTIVE,
                        SessionContextStore.SummaryStatus.STALE,
                        SessionContextStore.SummaryStatus.STALE);
        assertThat(store.activeSummaries("user", "website", "session")).containsExactly(rootSummary);
    }

    @Test
    void summaryGenerationRejectsInactiveSources() {
        SessionContextStore store = new SessionContextStore(8);
        SessionContextStore.MessageNode root = store.appendNode(
                "user", "website", "session", null, new SessionContextStore.Message("user", "root"));
        SessionContextStore.MessageNode branchA = store.appendNode(
                "user", "website", "session", null, new SessionContextStore.Message("assistant", "branch-a"));
        store.appendNode("user", "website", "session", root.messageId(),
                new SessionContextStore.Message("assistant", "branch-b"));

        assertThatThrownBy(() -> store.addSummary(
                "user", "website", "session", List.of(branchA.messageId()), "invalid", "model-r1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on the active branch");
    }

    @Test
    void persistenceFailureRollsBackLeafSelectionAndSummaryInvalidation() {
        RecordingPersistence persistence = new RecordingPersistence();
        SessionContextStore store = new SessionContextStore(8, persistence);
        SessionContextStore.MessageNode root = store.appendNode(
                "user", "website", "session", null, new SessionContextStore.Message("user", "root"));
        SessionContextStore.MessageNode branchA = store.appendNode(
                "user", "website", "session", null, new SessionContextStore.Message("assistant", "branch-a"));
        SessionContextStore.MessageNode branchB = store.appendNode(
                "user", "website", "session", root.messageId(),
                new SessionContextStore.Message("assistant", "branch-b"));
        store.selectActiveLeaf("user", "website", "session", branchA.messageId());
        store.addSummary("user", "website", "session", List.of(branchA.messageId()), "summary", "model-r1");
        SessionContextStore.GraphSnapshot before = store.graph("user", "website", "session");
        persistence.failNext = true;

        assertThatThrownBy(() -> store.selectActiveLeaf(
                "user", "website", "session", branchB.messageId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced persistence conflict");

        assertThat(store.graph("user", "website", "session")).isEqualTo(before);
        assertThat(store.activeSummaries("user", "website", "session"))
                .containsExactly(before.summaries().getFirst());
    }

    @Test
    void staleSummarySurvivesThePostgresJsonSnapshotBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SessionContextStore source = new SessionContextStore(8);
        SessionContextStore.MessageNode root = source.appendNode(
                "user", "website", "json", null, new SessionContextStore.Message("user", "root"));
        SessionContextStore.MessageNode branchA = source.appendNode(
                "user", "website", "json", null, new SessionContextStore.Message("assistant", "branch-a"));
        SessionContextStore.DerivedSummary active = source.addSummary(
                "user", "website", "json", List.of(branchA.messageId()), "summary", "model-r1");
        source.appendNode("user", "website", "json", root.messageId(),
                new SessionContextStore.Message("assistant", "branch-b"));
        SessionContextStore.GraphSnapshot serialized = mapper.readValue(
                mapper.writeValueAsString(source.graph("user", "website", "json")),
                SessionContextStore.GraphSnapshot.class);
        RecordingPersistence persistence = new RecordingPersistence();
        persistence.save(serialized);

        SessionContextStore restored = new SessionContextStore(8, persistence);
        SessionContextStore.DerivedSummary stale = restored.graph("user", "website", "json")
                .summaries().getFirst();

        assertThat(stale.status()).isEqualTo(SessionContextStore.SummaryStatus.STALE);
        assertThat(stale.sourceRevisionDigest()).isEqualTo(active.sourceRevisionDigest());
        assertThat(restored.activeSummaries("user", "website", "json")).isEmpty();
    }

    @Test
    void sourceRevisionMismatchStalesAPersistedActiveSummaryOnRestore() {
        SessionContextStore source = new SessionContextStore(8);
        SessionContextStore.MessageNode original = source.appendNode(
                "user", "website", "edited", null, new SessionContextStore.Message("user", "before edit"));
        source.addSummary("user", "website", "edited", List.of(original.messageId()),
                "summary", "model-r1");
        SessionContextStore.GraphSnapshot current = source.graph("user", "website", "edited");
        SessionContextStore.MessageNode edited = new SessionContextStore.MessageNode(
                original.messageId(), original.parentMessageId(), original.role(), "after edit", original.createdAt());
        SessionContextStore.GraphSnapshot persistedWithEditedSource = new SessionContextStore.GraphSnapshot(
                current.userId(), current.clientId(), current.sessionId(), current.activeLeafMessageId(),
                current.revision(), List.of(edited), List.of(edited), current.references(), current.summaries());
        RecordingPersistence persistence = new RecordingPersistence();
        persistence.save(persistedWithEditedSource);

        SessionContextStore restored = new SessionContextStore(8, persistence);

        assertThat(restored.graph("user", "website", "edited").summaries().getFirst().status())
                .isEqualTo(SessionContextStore.SummaryStatus.STALE);
        assertThat(restored.activeSummaries("user", "website", "edited")).isEmpty();
    }

    @Test
    void restoresLegacyGraphJsonWithoutDigestAsStaleAndWritesTheUpgradedShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SessionContextStore source = new SessionContextStore(8);
        SessionContextStore.MessageNode message = source.appendNode(
                "user", "website", "legacy", null, new SessionContextStore.Message("user", "legacy message"));
        source.addSummary("user", "website", "legacy", List.of(message.messageId()),
                "legacy summary", "model-r1");
        ObjectNode legacyTree = mapper.valueToTree(source.graph("user", "website", "legacy"));
        ObjectNode legacySummary = (ObjectNode) legacyTree.withArray("summaries").get(0);
        legacySummary.remove("source_revision_digest");
        legacySummary.remove("sourceRevisionDigest");
        legacySummary.remove("status");
        SessionContextStore.GraphSnapshot legacy = mapper.treeToValue(
                legacyTree, SessionContextStore.GraphSnapshot.class);
        RecordingPersistence persistence = new RecordingPersistence();
        persistence.save(legacy);

        SessionContextStore restored = new SessionContextStore(8, persistence);
        SessionContextStore.DerivedSummary upgraded = restored.graph("user", "website", "legacy")
                .summaries().getFirst();

        assertThat(upgraded.sourceRevisionDigest()).matches("[0-9a-f]{64}");
        assertThat(upgraded.status()).isEqualTo(SessionContextStore.SummaryStatus.STALE);
        assertThat(restored.activeSummaries("user", "website", "legacy")).isEmpty();
        assertThat(mapper.writeValueAsString(restored.graph("user", "website", "legacy")))
                .contains("\"source_revision_digest\":\"" + upgraded.sourceRevisionDigest() + "\"")
                .contains("\"status\":\"STALE\"");
    }

    @Test
    void failedPersistenceRollsBackTheInMemoryGraphMutation() {
        RecordingPersistence persistence = new RecordingPersistence();
        SessionContextStore store = new SessionContextStore(8, persistence);
        store.append("user", "website", "session", new SessionContextStore.Message("user", "saved"));
        SessionContextStore.GraphSnapshot before = store.graph("user", "website", "session");
        persistence.failNext = true;

        assertThatThrownBy(() -> store.append(
                        "user", "website", "session",
                        new SessionContextStore.Message("assistant", "ghost")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced persistence conflict");

        assertThat(store.graph("user", "website", "session")).isEqualTo(before);
    }

    private static final class RecordingPersistence implements SessionContextPersistence {
        private final Map<String, SessionContextStore.GraphSnapshot> values = new LinkedHashMap<>();
        private boolean failNext;

        @Override
        public List<SessionContextStore.GraphSnapshot> loadAll() {
            return List.copyOf(values.values());
        }

        @Override
        public void save(SessionContextStore.GraphSnapshot snapshot) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("forced persistence conflict");
            }
            values.put(snapshot.userId() + "\0" + snapshot.clientId() + "\0" + snapshot.sessionId(), snapshot);
        }

        @Override
        public void clear() {
            values.clear();
        }
    }
}
