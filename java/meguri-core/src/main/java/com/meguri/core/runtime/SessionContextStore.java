package com.meguri.core.runtime;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Companion Context adapter. Raw messages form an immutable DAG; active
 * history, summaries and typed references are persisted through a replaceable projection.
 */
public final class SessionContextStore {
    public record Message(String role, String content) {
        public Message {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
            if (content == null) throw new IllegalArgumentException("content must not be null");
        }
    }

    public record MessageNode(
            String messageId,
            String parentMessageId,
            String role,
            String content,
            Instant createdAt) { }

    public enum ReferenceType { QUOTE, RESUME_FROM, TOPIC_LINK }

    public record ContextReference(
            String referenceId,
            ReferenceType type,
            String sourceMessageId,
            String targetMessageId,
            Instant createdAt) { }

    public enum SummaryStatus { ACTIVE, STALE }

    public record DerivedSummary(
            String summaryId,
            List<String> sourceMessageIds,
            String content,
            String modelRevision,
            long contextRevision,
            Instant createdAt,
            @JsonProperty("source_revision_digest")
            @JsonAlias("sourceRevisionDigest")
            String sourceRevisionDigest,
            SummaryStatus status) {
        public DerivedSummary {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }

        private DerivedSummary markStale() {
            if (status == SummaryStatus.STALE) return this;
            return new DerivedSummary(summaryId, sourceMessageIds, content, modelRevision,
                    contextRevision, createdAt, sourceRevisionDigest, SummaryStatus.STALE);
        }
    }

    public record Snapshot(String userId, String clientId, String sessionId, List<Message> messages) { }
    public record GraphSnapshot(
            String userId,
            String clientId,
            String sessionId,
            String activeLeafMessageId,
            long revision,
            List<MessageNode> allNodes,
            List<MessageNode> activePath,
            List<ContextReference> references,
            List<DerivedSummary> summaries) {
        public GraphSnapshot {
            allNodes = allNodes == null ? List.of() : List.copyOf(allNodes);
            activePath = activePath == null ? List.of() : List.copyOf(activePath);
            references = references == null ? List.of() : List.copyOf(references);
            summaries = summaries == null ? List.of() : List.copyOf(summaries);
        }
    }

    private record Scope(String userId, String clientId, String sessionId) { }

    private static final class GraphState {
        private final LinkedHashMap<String, MessageNode> nodes = new LinkedHashMap<>();
        private final List<ContextReference> references = new ArrayList<>();
        private final List<DerivedSummary> summaries = new ArrayList<>();
        private final AtomicLong revision = new AtomicLong();
        private String activeLeafMessageId;
    }

    private final int capacity;
    private final SessionContextPersistence persistence;
    private final Map<Scope, GraphState> sessions = new ConcurrentHashMap<>();

    public SessionContextStore() {
        this(20, new NoopSessionContextPersistence());
    }

    public SessionContextStore(int capacity) {
        this(capacity, new NoopSessionContextPersistence());
    }

    public SessionContextStore(int capacity, SessionContextPersistence persistence) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        restore(persistence.loadAll());
    }

    public void append(String userId, String clientId, String sessionId, Message message) {
        appendNode(userId, clientId, sessionId, null, message);
    }

    public MessageNode appendNode(String userId, String clientId, String sessionId,
                                  String parentMessageId, Message message) {
        Scope scope = key(userId, clientId, sessionId);
        GraphState graph = sessions.computeIfAbsent(scope, ignored -> new GraphState());
        synchronized (graph) {
            String parent = parentMessageId == null ? graph.activeLeafMessageId : parentMessageId;
            if (parent != null && !graph.nodes.containsKey(parent)) {
                throw new IllegalArgumentException("parent message does not exist in this session");
            }
            GraphState before = copyOf(graph);
            MessageNode node = new MessageNode(newId("msg"), parent, message.role(), message.content(), Instant.now());
            graph.nodes.put(node.messageId(), node);
            graph.activeLeafMessageId = node.messageId();
            invalidateSummariesWithChangedSources(graph);
            graph.revision.incrementAndGet();
            persist(scope, graph, before);
            return node;
        }
    }

    public List<Message> recent(String userId, String clientId, String sessionId) {
        GraphState graph = sessions.get(key(userId, clientId, sessionId));
        if (graph == null) return List.of();
        synchronized (graph) {
            List<MessageNode> path = activePath(graph);
            return path.stream()
                    .skip(Math.max(0, path.size() - capacity))
                    .map(node -> new Message(node.role(), node.content()))
                    .toList();
        }
    }

    /** Selects a new immutable leaf instead of mutating the original assistant node. */
    public boolean replaceLastAssistant(String userId, String clientId, String sessionId,
                                        String expectedContent, String replacementContent) {
        GraphState graph = sessions.get(key(userId, clientId, sessionId));
        if (graph == null || replacementContent == null || replacementContent.isBlank()) return false;
        synchronized (graph) {
            MessageNode current = graph.activeLeafMessageId == null ? null : graph.nodes.get(graph.activeLeafMessageId);
            if (current == null || !"assistant".equals(current.role()) || !current.content().equals(expectedContent)) {
                return false;
            }
            GraphState before = copyOf(graph);
            MessageNode replacement = new MessageNode(
                    newId("msg"), current.parentMessageId(), "assistant", replacementContent, Instant.now());
            graph.nodes.put(replacement.messageId(), replacement);
            graph.activeLeafMessageId = replacement.messageId();
            graph.references.add(new ContextReference(
                    newId("ref"), ReferenceType.RESUME_FROM,
                    current.messageId(), replacement.messageId(), Instant.now()));
            invalidateSummariesWithChangedSources(graph);
            graph.revision.incrementAndGet();
            persist(key(userId, clientId, sessionId), graph, before);
            return true;
        }
    }

    /** Selects an existing immutable branch without reviving summaries invalidated by an earlier switch. */
    public boolean selectActiveLeaf(String userId, String clientId, String sessionId, String messageId) {
        Scope scope = key(userId, clientId, sessionId);
        GraphState graph = sessions.get(scope);
        if (graph == null || messageId == null || messageId.isBlank()) return false;
        synchronized (graph) {
            if (!graph.nodes.containsKey(messageId)) return false;
            if (messageId.equals(graph.activeLeafMessageId)) return true;
            GraphState before = copyOf(graph);
            graph.activeLeafMessageId = messageId;
            invalidateSummariesWithChangedSources(graph);
            graph.revision.incrementAndGet();
            persist(scope, graph, before);
            return true;
        }
    }

    /**
     * Links and projects a candidate topic onto the parent active branch. The
     * candidate graph remains intact for audit and later re-selection.
     */
    public void mergeInto(String userId, String clientId, String parentSessionId, String candidateSessionId) {
        if (parentSessionId.equals(candidateSessionId)) return;
        Scope parentScope = key(userId, clientId, parentSessionId);
        Scope candidateScope = key(userId, clientId, candidateSessionId);
        GraphState candidate = sessions.get(candidateScope);
        if (candidate == null) return;
        GraphState parent = sessions.computeIfAbsent(parentScope, ignored -> new GraphState());

        String candidateLeaf;
        List<MessageNode> candidatePath;
        synchronized (candidate) {
            candidateLeaf = candidate.activeLeafMessageId;
            candidatePath = activePath(candidate);
        }
        synchronized (parent) {
            GraphState before = copyOf(parent);
            String parentLeafBefore = parent.activeLeafMessageId;
            for (MessageNode source : candidatePath) {
                MessageNode projected = new MessageNode(
                        newId("msg"), parent.activeLeafMessageId,
                        source.role(), source.content(), source.createdAt());
                parent.nodes.put(projected.messageId(), projected);
                parent.activeLeafMessageId = projected.messageId();
            }
            if (candidateLeaf != null && parent.activeLeafMessageId != null) {
                parent.references.add(new ContextReference(
                        newId("ref"), ReferenceType.TOPIC_LINK,
                        candidateLeaf, parentLeafBefore, Instant.now()));
            }
            invalidateSummariesWithChangedSources(parent);
            parent.revision.incrementAndGet();
            persist(parentScope, parent, before);
        }
    }

    public DerivedSummary addSummary(String userId, String clientId, String sessionId,
                                     List<String> sourceMessageIds, String content, String modelRevision) {
        Scope scope = key(userId, clientId, sessionId);
        GraphState graph = sessions.computeIfAbsent(scope, ignored -> new GraphState());
        synchronized (graph) {
            List<String> sources = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("summary must have at least one source message");
            }
            if (new HashSet<>(sources).size() != sources.size()) {
                throw new IllegalArgumentException("summary source messages must be unique");
            }
            Set<String> activeMessageIds = activeMessageIds(graph);
            for (String sourceMessageId : sources) {
                if (!graph.nodes.containsKey(sourceMessageId)) {
                    throw new IllegalArgumentException("summary source message does not exist: " + sourceMessageId);
                }
                if (!activeMessageIds.contains(sourceMessageId)) {
                    throw new IllegalArgumentException("summary source message is not on the active branch: "
                            + sourceMessageId);
                }
            }
            GraphState before = copyOf(graph);
            long summaryRevision = graph.revision.incrementAndGet();
            DerivedSummary summary = new DerivedSummary(
                    newId("summary"), sources, content, modelRevision,
                    summaryRevision, Instant.now(), sourceRevisionDigest(graph, sources), SummaryStatus.ACTIVE);
            graph.summaries.add(summary);
            persist(scope, graph, before);
            return summary;
        }
    }

    /** Safe summary feed for active-context and prompt assembly; audit history remains available via graph(). */
    public List<DerivedSummary> activeSummaries(String userId, String clientId, String sessionId) {
        GraphState graph = sessions.get(key(userId, clientId, sessionId));
        if (graph == null) return List.of();
        synchronized (graph) {
            return graph.summaries.stream()
                    .filter(summary -> summary.status() == SummaryStatus.ACTIVE)
                    .filter(summary -> hasCurrentSources(graph, summary))
                    .toList();
        }
    }

    public ContextReference link(String userId, String clientId, String sessionId,
                                 ReferenceType type, String sourceMessageId, String targetMessageId) {
        Objects.requireNonNull(type, "type");
        Scope scope = key(userId, clientId, sessionId);
        GraphState graph = sessions.get(scope);
        if (graph == null) throw new IllegalArgumentException("session context does not exist");
        synchronized (graph) {
            if (!graph.nodes.containsKey(sourceMessageId) || !graph.nodes.containsKey(targetMessageId)) {
                throw new IllegalArgumentException("reference messages must exist in the same session");
            }
            GraphState before = copyOf(graph);
            ContextReference reference = new ContextReference(
                    newId("ref"), type, sourceMessageId, targetMessageId, Instant.now());
            graph.references.add(reference);
            graph.revision.incrementAndGet();
            persist(scope, graph, before);
            return reference;
        }
    }

    public long revision(String userId, String clientId, String sessionId) {
        GraphState graph = sessions.get(key(userId, clientId, sessionId));
        return graph == null ? 0L : graph.revision.get();
    }

    public GraphSnapshot graph(String userId, String clientId, String sessionId) {
        Scope scope = key(userId, clientId, sessionId);
        GraphState graph = sessions.get(scope);
        if (graph == null) {
            return new GraphSnapshot(scope.userId(), scope.clientId(), scope.sessionId(), null, 0L,
                    List.of(), List.of(), List.of(), List.of());
        }
        synchronized (graph) {
            return new GraphSnapshot(scope.userId(), scope.clientId(), scope.sessionId(),
                    graph.activeLeafMessageId, graph.revision.get(), List.copyOf(graph.nodes.values()), activePath(graph),
                    List.copyOf(graph.references), List.copyOf(graph.summaries));
        }
    }

    /** Immutable active-branch snapshots for the sleep-time summary job. */
    public List<Snapshot> snapshots() {
        List<Snapshot> snapshots = new ArrayList<>();
        sessions.forEach((scope, graph) -> {
            synchronized (graph) {
                List<Message> messages = activePath(graph).stream()
                        .map(node -> new Message(node.role(), node.content()))
                        .toList();
                if (!messages.isEmpty()) {
                    snapshots.add(new Snapshot(scope.userId(), scope.clientId(), scope.sessionId(), messages));
                }
            }
        });
        return List.copyOf(snapshots);
    }

    public void clear() {
        sessions.clear();
        persistence.clear();
    }

    private void restore(List<GraphSnapshot> snapshots) {
        if (snapshots == null) return;
        for (GraphSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            Scope scope = key(snapshot.userId(), snapshot.clientId(), snapshot.sessionId());
            GraphState graph = new GraphState();
            for (MessageNode node : snapshot.allNodes()) {
                if (node != null && graph.nodes.putIfAbsent(node.messageId(), node) != null) {
                    throw new IllegalStateException("persisted context contains duplicate message IDs");
                }
            }
            graph.references.addAll(snapshot.references());
            graph.summaries.addAll(snapshot.summaries());
            graph.revision.set(Math.max(0L, snapshot.revision()));
            graph.activeLeafMessageId = snapshot.activeLeafMessageId();
            if (graph.activeLeafMessageId != null && !graph.nodes.containsKey(graph.activeLeafMessageId)) {
                throw new IllegalStateException("persisted active context leaf does not exist");
            }
            validateRestoredGraph(graph);
            normalizeRestoredSummaries(graph);
            sessions.put(scope, graph);
        }
    }

    private void persist(Scope scope, GraphState graph, GraphState before) {
        try {
            persistence.save(snapshot(scope, graph));
        } catch (RuntimeException error) {
            restoreState(graph, before);
            throw error;
        }
    }

    private static GraphState copyOf(GraphState source) {
        GraphState copy = new GraphState();
        copy.nodes.putAll(source.nodes);
        copy.references.addAll(source.references);
        copy.summaries.addAll(source.summaries);
        copy.revision.set(source.revision.get());
        copy.activeLeafMessageId = source.activeLeafMessageId;
        return copy;
    }

    private static void restoreState(GraphState target, GraphState source) {
        target.nodes.clear();
        target.nodes.putAll(source.nodes);
        target.references.clear();
        target.references.addAll(source.references);
        target.summaries.clear();
        target.summaries.addAll(source.summaries);
        target.revision.set(source.revision.get());
        target.activeLeafMessageId = source.activeLeafMessageId;
    }

    private static void validateRestoredGraph(GraphState graph) {
        for (MessageNode node : graph.nodes.values()) {
            if (node == null || node.messageId() == null || node.messageId().isBlank()
                    || node.role() == null || node.role().isBlank() || node.content() == null
                    || node.createdAt() == null) {
                throw new IllegalStateException("persisted context contains an invalid message node");
            }
            if (node.parentMessageId() != null && !graph.nodes.containsKey(node.parentMessageId())) {
                throw new IllegalStateException("persisted context message parent does not exist");
            }
            java.util.HashSet<String> path = new java.util.HashSet<>();
            MessageNode cursor = node;
            while (cursor != null) {
                if (!path.add(cursor.messageId())) {
                    throw new IllegalStateException("persisted context message graph contains a cycle");
                }
                cursor = cursor.parentMessageId() == null ? null : graph.nodes.get(cursor.parentMessageId());
            }
        }
        for (ContextReference reference : graph.references) {
            if (reference == null || reference.type() == null || reference.referenceId() == null
                    || reference.referenceId().isBlank() || reference.createdAt() == null) {
                throw new IllegalStateException("persisted context contains an invalid reference");
            }
            if (reference.type() != ReferenceType.TOPIC_LINK
                    && (!graph.nodes.containsKey(reference.sourceMessageId())
                    || !graph.nodes.containsKey(reference.targetMessageId()))) {
                throw new IllegalStateException("persisted context reference endpoint does not exist");
            }
        }
        for (DerivedSummary summary : graph.summaries) {
            if (summary == null || summary.summaryId() == null || summary.summaryId().isBlank()
                    || summary.sourceMessageIds().isEmpty() || summary.content() == null
                    || summary.modelRevision() == null || summary.createdAt() == null
                    || !graph.nodes.keySet().containsAll(summary.sourceMessageIds())) {
                throw new IllegalStateException("persisted context summary source does not exist");
            }
        }
    }

    private static void normalizeRestoredSummaries(GraphState graph) {
        for (int index = 0; index < graph.summaries.size(); index++) {
            DerivedSummary summary = graph.summaries.get(index);
            String digest = summary.sourceRevisionDigest();
            boolean missingDigest = digest == null || digest.isBlank();
            if (missingDigest) {
                digest = sourceRevisionDigest(graph, summary.sourceMessageIds());
            }
            SummaryStatus status = summary.status();
            DerivedSummary normalized = new DerivedSummary(
                    summary.summaryId(), summary.sourceMessageIds(), summary.content(), summary.modelRevision(),
                    summary.contextRevision(), summary.createdAt(), digest,
                    missingDigest || status == SummaryStatus.STALE
                            ? SummaryStatus.STALE : SummaryStatus.ACTIVE);
            if (!hasCurrentSources(graph, normalized)) normalized = normalized.markStale();
            graph.summaries.set(index, normalized);
        }
    }

    private static void invalidateSummariesWithChangedSources(GraphState graph) {
        for (int index = 0; index < graph.summaries.size(); index++) {
            DerivedSummary summary = graph.summaries.get(index);
            if (summary.status() == SummaryStatus.ACTIVE && !hasCurrentSources(graph, summary)) {
                graph.summaries.set(index, summary.markStale());
            }
        }
    }

    private static boolean hasCurrentSources(GraphState graph, DerivedSummary summary) {
        if (summary.sourceRevisionDigest() == null || summary.sourceRevisionDigest().isBlank()
                || summary.sourceMessageIds().isEmpty()
                || !activeMessageIds(graph).containsAll(summary.sourceMessageIds())) {
            return false;
        }
        return summary.sourceRevisionDigest().equals(
                sourceRevisionDigest(graph, summary.sourceMessageIds()));
    }

    private static Set<String> activeMessageIds(GraphState graph) {
        Set<String> messageIds = new HashSet<>();
        for (MessageNode node : activePath(graph)) messageIds.add(node.messageId());
        return messageIds;
    }

    private static String sourceRevisionDigest(GraphState graph, List<String> sourceMessageIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "meguri.session-summary-source.v1");
            for (String sourceMessageId : sourceMessageIds) {
                MessageNode node = graph.nodes.get(sourceMessageId);
                if (node == null) {
                    throw new IllegalStateException("summary source message does not exist: " + sourceMessageId);
                }
                updateDigest(digest, node.messageId());
                updateDigest(digest, node.parentMessageId());
                updateDigest(digest, node.role());
                updateDigest(digest, node.content());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static GraphSnapshot snapshot(Scope scope, GraphState graph) {
        return new GraphSnapshot(
                scope.userId(), scope.clientId(), scope.sessionId(),
                graph.activeLeafMessageId, graph.revision.get(),
                List.copyOf(graph.nodes.values()), activePath(graph),
                List.copyOf(graph.references), List.copyOf(graph.summaries));
    }

    private static List<MessageNode> activePath(GraphState graph) {
        if (graph.activeLeafMessageId == null) return List.of();
        List<MessageNode> reverse = new ArrayList<>();
        String cursor = graph.activeLeafMessageId;
        while (cursor != null) {
            MessageNode node = graph.nodes.get(cursor);
            if (node == null) break;
            reverse.add(node);
            cursor = node.parentMessageId();
        }
        Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    private static Scope key(String userId, String clientId, String sessionId) {
        return new Scope(String.valueOf(userId), String.valueOf(clientId), String.valueOf(sessionId));
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
