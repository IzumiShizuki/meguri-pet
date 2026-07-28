package com.meguri.core.retrieval;

import com.meguri.core.knowledge.ChunkRole;
import com.meguri.core.knowledge.DeterministicSearchProjector;
import com.meguri.core.knowledge.KnowledgeAcl;
import com.meguri.core.knowledge.KnowledgeChunk;
import com.meguri.core.knowledge.KnowledgeDocument;
import com.meguri.core.knowledge.KnowledgeDocumentVersion;
import com.meguri.core.knowledge.KnowledgeEntity;
import com.meguri.core.knowledge.KnowledgeRelation;
import com.meguri.core.knowledge.KnowledgeRepository;
import com.meguri.core.knowledge.KnowledgeTermProjection;
import com.meguri.core.knowledge.KnowledgeVectorProjection;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Production bridge from immutable Knowledge projections to typed Hybrid and Graph retrieval.
 */
public final class KnowledgeRepositoryRetrievalBridge implements
        RetrievalProvider, EntityAliasResolverPort, KnowledgeGraphStorePort, GraphVisibilityPort {
    private static final int MAX_CANDIDATE_MULTIPLIER = 4;
    private static final int DEFAULT_PARENT_CONTEXT_TOKEN_LIMIT = 512;
    private final KnowledgeSearchProjectionPort projection;
    private final String fixedTenantId;
    private final KnowledgeVectorizer vectorizer;
    private final WeightedRrfFusion fusion;
    private final int parentContextTokenLimit;

    public KnowledgeRepositoryRetrievalBridge(KnowledgeRepository repository, String tenantId) {
        this(new KnowledgeRepositoryProjectionAdapter(repository), tenantId,
                text -> DeterministicSearchProjector.embedding(text).stream()
                        .mapToDouble(Double::doubleValue)
                        .toArray(),
                new WeightedRrfFusion(60, Map.of(
                        RankSignal.KEYWORD, 1.0,
                        RankSignal.VECTOR, 1.0)),
                DEFAULT_PARENT_CONTEXT_TOKEN_LIMIT);
    }

    public KnowledgeRepositoryRetrievalBridge(
            KnowledgeSearchProjectionPort projection,
            String tenantId,
            KnowledgeVectorizer vectorizer,
            WeightedRrfFusion fusion) {
        this(projection, tenantId, vectorizer, fusion,
                DEFAULT_PARENT_CONTEXT_TOKEN_LIMIT);
    }

    public KnowledgeRepositoryRetrievalBridge(
            KnowledgeSearchProjectionPort projection,
            String tenantId,
            KnowledgeVectorizer vectorizer,
            WeightedRrfFusion fusion,
            int parentContextTokenLimit) {
        this.projection = java.util.Objects.requireNonNull(projection);
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        this.fixedTenantId = tenantId.trim();
        this.vectorizer = java.util.Objects.requireNonNull(vectorizer);
        this.fusion = java.util.Objects.requireNonNull(fusion);
        this.parentContextTokenLimit = positiveTokenLimit(
                parentContextTokenLimit);
    }

    public KnowledgeRepositoryRetrievalBridge(
            KnowledgeSearchProjectionPort projection,
            KnowledgeVectorizer vectorizer,
            WeightedRrfFusion fusion) {
        this(projection, vectorizer, fusion,
                DEFAULT_PARENT_CONTEXT_TOKEN_LIMIT);
    }

    public KnowledgeRepositoryRetrievalBridge(
            KnowledgeSearchProjectionPort projection,
            KnowledgeVectorizer vectorizer,
            WeightedRrfFusion fusion,
            int parentContextTokenLimit) {
        this.projection = java.util.Objects.requireNonNull(projection);
        this.fixedTenantId = null;
        this.vectorizer = java.util.Objects.requireNonNull(vectorizer);
        this.fusion = java.util.Objects.requireNonNull(fusion);
        this.parentContextTokenLimit = positiveTokenLimit(
                parentContextTokenLimit);
    }

    public FrozenKnowledgeSnapshot freezeForNewTurn(Instant at) {
        if (fixedTenantId == null) {
            throw new IllegalStateException("tenantId is required to freeze a shared bridge");
        }
        return FrozenKnowledgeSnapshot.freeze(projection, at, fixedTenantId);
    }

    public FrozenKnowledgeSnapshot freezeForNewTurn(Instant at, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String effectiveTenant = tenantId.trim();
        if (fixedTenantId != null && !fixedTenantId.equals(effectiveTenant)) {
            throw new IllegalArgumentException("tenantId does not match the bridge");
        }
        return FrozenKnowledgeSnapshot.freeze(projection, at, effectiveTenant);
    }

    public FrozenKnowledgeSnapshot restoreSnapshot(String snapshotId) {
        FrozenKnowledgeSnapshot snapshot = FrozenKnowledgeSnapshot.restore(snapshotId);
        snapshot.loadAndValidate(projection);
        return snapshot;
    }

    public FrozenKnowledgeSnapshot snapshotFromVersions(Set<String> versionIds) {
        return FrozenKnowledgeSnapshot.fromVersionIds(projection, versionIds);
    }

    @Override
    public List<RetrievalItem> retrieve(
            String query, int limit, RetrievalContext context) {
        return retrieveWithDiagnostics(query, limit, context).items();
    }

    @Override
    public RetrievalProviderResult retrieveWithDiagnostics(
            String query, int limit, RetrievalContext context) {
        return retrieveResult(query, limit, context, snapshot(context));
    }

    public List<RetrievalItem> retrieve(
            String query, int limit, RetrievalContext context, Set<String> versionIds) {
        return retrieveResult(query, limit, context,
                FrozenKnowledgeSnapshot.fromVersionIds(projection, versionIds)).items();
    }

    private RetrievalProviderResult retrieveResult(
            String query, int limit, RetrievalContext context,
            FrozenKnowledgeSnapshot snapshot) {
        if (query == null || query.isBlank() || limit <= 0) {
            return RetrievalProviderResult.success(List.of());
        }
        ProjectionView view = load(snapshot);
        int boundedLimit = Math.min(limit, RetrievalGate.MAX_SOURCE_ITEMS);
        int candidateLimit = Math.min(RetrievalGate.MAX_SOURCE_ITEMS,
                boundedLimit * MAX_CANDIDATE_MULTIPLIER);
        List<KnowledgeChunk> children = view.chunks.values().stream()
                .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                .filter(chunk -> visible(chunk.acl(), context))
                .filter(chunk -> chunk.effectiveAt(context.validAt()))
                .filter(chunk -> validChunk(chunk, view))
                .filter(chunk -> restorableParent(chunk, view, context).isPresent())
                .toList();
        Set<String> aclHashes = children.stream().map(chunk -> chunk.acl().hash())
                .collect(Collectors.toSet());
        Map<String, KnowledgeChunk> authorized = children.stream()
                .collect(Collectors.toMap(KnowledgeChunk::id, Function.identity()));
        RankedLane keywordLane = rankKeyword(
                query, candidateLimit, snapshot, context, children, view,
                aclHashes, authorized);
        RankedLane vectorLane = rankVector(
                query, candidateLimit, snapshot, context, children, view,
                aclHashes, authorized);
        List<KnowledgeChunk> keyword = keywordLane.chunks();
        List<KnowledgeChunk> vector = vectorLane.chunks();
        Map<String, RetrievalItem> candidates = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : concat(keyword, vector)) {
            toItem(chunk, view, context).ifPresent(item -> candidates.putIfAbsent(chunk.id(), item));
        }
        Map<String, RetrievalItem> finalCandidates = candidates;
        List<RetrievalItem> items = fusion.fuse(Map.of(
                        RankSignal.KEYWORD, keyword.stream()
                                .map(chunk -> finalCandidates.get(chunk.id()))
                                .filter(java.util.Objects::nonNull).toList(),
                        RankSignal.VECTOR, vector.stream()
                                .map(chunk -> finalCandidates.get(chunk.id()))
                                .filter(java.util.Objects::nonNull).toList()))
                .stream().limit(boundedLimit).toList();
        LinkedHashSet<String> degradations = new LinkedHashSet<>(keywordLane.degradations());
        degradations.addAll(vectorLane.degradations());
        return new RetrievalProviderResult(items, List.copyOf(degradations));
    }

    private RankedLane rankKeyword(
            String query,
            int candidateLimit,
            FrozenKnowledgeSnapshot snapshot,
            RetrievalContext context,
            List<KnowledgeChunk> children,
            ProjectionView view,
            Set<String> aclHashes,
            Map<String, KnowledgeChunk> authorized) {
        try {
            Optional<List<String>> nativeRanks = projection.nativeKeywordRanks(
                    snapshot.versionIds(), aclHashes, context.validAt(), query, candidateLimit);
            if (nativeRanks.isPresent()) {
                return RankedLane.success(nativeRankedChunks(
                        nativeRanks.orElseThrow(), authorized, "keyword"));
            }
            return RankedLane.success(localKeyword(query, candidateLimit, children, view));
        } catch (SecurityException authorizationFailure) {
            throw authorizationFailure;
        } catch (RuntimeException nativeFailure) {
            try {
                return RankedLane.degraded(
                        localKeyword(query, candidateLimit, children, view),
                        "knowledge_keyword_native_failure");
            } catch (RuntimeException localFailure) {
                return RankedLane.degraded(List.of(), "knowledge_keyword_failure");
            }
        }
    }

    private RankedLane rankVector(
            String query,
            int candidateLimit,
            FrozenKnowledgeSnapshot snapshot,
            RetrievalContext context,
            List<KnowledgeChunk> children,
            ProjectionView view,
            Set<String> aclHashes,
            Map<String, KnowledgeChunk> authorized) {
        double[] queryVector;
        try {
            queryVector = vectorizer.vectorize(query);
        } catch (RuntimeException vectorizationFailure) {
            return RankedLane.degraded(List.of(), "knowledge_vector_failure");
        }
        try {
            Optional<List<String>> nativeRanks = projection.nativeVectorRanks(
                    snapshot.versionIds(), aclHashes, context.validAt(),
                    java.util.Arrays.stream(queryVector).boxed().toList(), candidateLimit);
            if (nativeRanks.isPresent()) {
                return RankedLane.success(nativeRankedChunks(
                        nativeRanks.orElseThrow(), authorized, "vector"));
            }
            return RankedLane.success(localVector(queryVector, candidateLimit, children, view));
        } catch (SecurityException authorizationFailure) {
            throw authorizationFailure;
        } catch (RuntimeException nativeFailure) {
            try {
                return RankedLane.degraded(
                        localVector(queryVector, candidateLimit, children, view),
                        "knowledge_vector_native_failure");
            } catch (RuntimeException localFailure) {
                return RankedLane.degraded(List.of(), "knowledge_vector_failure");
            }
        }
    }

    private List<KnowledgeChunk> localKeyword(
            String query, int candidateLimit,
            List<KnowledgeChunk> children, ProjectionView view) {
        return bm25(query, children, view).stream()
                .limit(candidateLimit).map(ScoredChunk::chunk).toList();
    }

    private List<KnowledgeChunk> localVector(
            double[] queryVector, int candidateLimit,
            List<KnowledgeChunk> children, ProjectionView view) {
        return vector(queryVector, children, view).stream()
                .limit(candidateLimit).map(ScoredChunk::chunk).toList();
    }

    @Override
    public List<EntityResolution> resolve(
            String query, List<String> entityMentions, RetrievalContext context) {
        ProjectionView view = load(snapshot(context));
        Set<String> normalizedMentions = new LinkedHashSet<>();
        if (entityMentions != null) {
            entityMentions.stream().map(KnowledgeRepositoryRetrievalBridge::normalize)
                    .filter(value -> !value.isBlank()).forEach(normalizedMentions::add);
        }
        String normalizedQuery = normalize(query);
        return view.entities.values().stream()
                .filter(entity -> visible(entity.acl(), context))
                .filter(entity -> validEntity(entity, view, context))
                .map(entity -> {
                    Set<String> names = entity.aliases().stream()
                            .map(KnowledgeRepositoryRetrievalBridge::normalize)
                            .filter(value -> !value.isBlank())
                            .collect(Collectors.toSet());
                    double confidence = names.stream()
                            .anyMatch(normalizedMentions::contains)
                            ? 1.0
                            : names.stream().anyMatch(normalizedQuery::contains)
                                    ? 0.8 : 0;
                    return confidence == 0 ? null
                            : new EntityResolution(entity.canonicalName(), entity.id(), confidence);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(EntityResolution::confidence).reversed()
                        .thenComparing(EntityResolution::entityId))
                .limit(RetrievalGate.MAX_SOURCE_ITEMS)
                .toList();
    }

    @Override
    public List<GraphPath> findPaths(
            List<String> normalizedEntityIds, int maxHops, int limit,
            RetrievalContext context) {
        if (maxHops < 1 || maxHops > 3 || limit <= 0) return List.of();
        int boundedLimit = Math.min(limit, RetrievalGate.MAX_SOURCE_ITEMS);
        ProjectionView view = load(snapshot(context));
        Set<String> starts = normalizedEntityIds == null
                ? Set.of() : normalizedEntityIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .limit(RetrievalGate.MAX_SOURCE_ITEMS)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> visibleEntities = view.entities.values().stream()
                .filter(entity -> visible(entity.acl(), context))
                .filter(entity -> validEntity(entity, view, context))
                .map(KnowledgeEntity::id).collect(Collectors.toSet());
        if (!visibleEntities.containsAll(starts)) return List.of();

        Map<String, List<KnowledgeRelation>> outgoing = view.relations.values().stream()
                .filter(relation -> visible(relation.acl(), context))
                .filter(relation -> validRelation(relation, view, context))
                .filter(relation -> visibleEntities.contains(relation.fromEntityId())
                        && visibleEntities.contains(relation.toEntityId()))
                .sorted(Comparator.comparing(KnowledgeRelation::id))
                .collect(Collectors.groupingBy(
                        KnowledgeRelation::fromEntityId, LinkedHashMap::new, Collectors.toList()));
        ArrayList<GraphPath> paths = new ArrayList<>();
        int expansionLimit = Math.max(64, boundedLimit * 64);
        int expansions = 0;
        for (String start : starts.stream().sorted().toList()) {
            ArrayDeque<PathState> queue = new ArrayDeque<>();
            queue.add(new PathState(List.of(start), List.of()));
            while (!queue.isEmpty() && paths.size() < boundedLimit
                    && expansions++ < expansionLimit) {
                PathState state = queue.removeFirst();
                if (!state.edges.isEmpty()
                        && (starts.size() == 1 || starts.contains(state.nodes.getLast()))) {
                    paths.add(toGraphPath(state));
                }
                if (state.edges.size() == maxHops) continue;
                for (KnowledgeRelation relation
                        : outgoing.getOrDefault(state.nodes.getLast(), List.of())) {
                    if (state.nodes.contains(relation.toEntityId())) continue;
                    ArrayList<String> nodes = new ArrayList<>(state.nodes);
                    nodes.add(relation.toEntityId());
                    ArrayList<KnowledgeRelation> edges = new ArrayList<>(state.edges);
                    edges.add(relation);
                    queue.addLast(new PathState(List.copyOf(nodes), List.copyOf(edges)));
                }
            }
        }
        return paths.stream()
                .sorted(Comparator.comparingDouble(GraphPath::score).reversed()
                        .thenComparing(path -> String.join("/", path.nodeIds())))
                .limit(boundedLimit).toList();
    }

    @Override
    public Map<String, EvidenceChunk> expandEvidence(
            Collection<String> chunkIds, RetrievalContext context) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        ProjectionView view = load(snapshot(context));
        LinkedHashMap<String, EvidenceChunk> result = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(chunkIds)) {
            KnowledgeChunk chunk = view.chunks.get(id);
            if (chunk != null && chunk.role() == ChunkRole.CHILD
                    && visible(chunk.acl(), context)
                    && chunk.effectiveAt(context.validAt())
                    && validChunk(chunk, view)) {
                result.put(id, new EvidenceChunk(
                        id, chunk.content(), citation(chunk, view), 0.85,
                        estimateTokens(chunk.content()), chunk.validFrom()));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public Visibility visibility(
            ResourceKind kind, String resourceId, RetrievalContext context) {
        ProjectionView view;
        try {
            view = load(snapshot(context));
        } catch (RuntimeException error) {
            return Visibility.VERSION_MISMATCH;
        }
        return switch (kind) {
            case ENTITY -> visibility(view.entities.get(resourceId), view, context);
            case EDGE -> visibility(view.relations.get(resourceId), view, context);
            case EVIDENCE_CHUNK -> visibility(view.chunks.get(resourceId), view, context);
        };
    }

    private Visibility visibility(
            KnowledgeEntity entity, ProjectionView view, RetrievalContext context) {
        if (entity == null || !view.versions.containsKey(entity.documentVersionId())) {
            return Visibility.VERSION_MISMATCH;
        }
        if (!visible(entity.acl(), context)) return Visibility.ACL_DENIED;
        return validEntity(entity, view, context) ? Visibility.VISIBLE : Visibility.INACTIVE;
    }

    private Visibility visibility(
            KnowledgeRelation relation, ProjectionView view, RetrievalContext context) {
        if (relation == null || !view.versions.containsKey(relation.documentVersionId())) {
            return Visibility.VERSION_MISMATCH;
        }
        if (!visible(relation.acl(), context)) return Visibility.ACL_DENIED;
        return validRelation(relation, view, context) ? Visibility.VISIBLE : Visibility.INACTIVE;
    }

    private Visibility visibility(
            KnowledgeChunk chunk, ProjectionView view, RetrievalContext context) {
        if (chunk == null || !view.versions.containsKey(chunk.documentVersionId())) {
            return Visibility.VERSION_MISMATCH;
        }
        if (!visible(chunk.acl(), context)) return Visibility.ACL_DENIED;
        return validChunk(chunk, view) && chunk.effectiveAt(context.validAt())
                ? Visibility.VISIBLE : Visibility.INACTIVE;
    }

    private Optional<RetrievalItem> toItem(
            KnowledgeChunk child, ProjectionView view, RetrievalContext context) {
        Optional<KnowledgeChunk> restored = restorableParent(child, view, context);
        if (restored.isEmpty()) return Optional.empty();
        KnowledgeChunk parent = restored.orElseThrow();
        RestoredContext restoredContext = restoreContext(parent, child);
        return Optional.of(new RetrievalItem(
                SourceType.KNOWLEDGE, child.id(), restoredContext.content(),
                citation(child, view), 0.85, RankTrace.empty(),
                restoredContext.tokenCount(), context.validAt(), null,
                List.of(child.id()), restoredContext.degradations(),
                context.traceId()));
    }

    private Optional<KnowledgeChunk> restorableParent(
            KnowledgeChunk child, ProjectionView view, RetrievalContext context) {
        KnowledgeChunk parent = view.chunks.get(child.parentChunkId());
        if (parent == null || parent.role() != ChunkRole.PARENT
                || !parent.documentVersionId().equals(child.documentVersionId())
                || !parent.documentId().equals(child.documentId())
                || !parent.acl().equals(child.acl())
                || !parent.validFrom().equals(child.validFrom())
                || !parent.validUntil().equals(child.validUntil())
                || !parent.effectiveAt(context.validAt())
                || !visible(parent.acl(), context)
                || !validChunk(parent, view)) {
            return Optional.empty();
        }
        return Optional.of(parent);
    }

    private List<ScoredChunk> bm25(
            String query, List<KnowledgeChunk> chunks, ProjectionView view) {
        if (view.searchProjectionsAuthoritative) {
            return projectedBm25(query, chunks, view);
        }
        return compatibilityBm25(query, chunks);
    }

    private List<ScoredChunk> projectedBm25(
            String query, List<KnowledgeChunk> chunks, ProjectionView view) {
        Set<String> queryTerms = DeterministicSearchProjector.termFrequencies(query).keySet();
        if (queryTerms.isEmpty() || chunks.isEmpty()) return List.of();
        double averageLength = chunks.stream()
                .map(chunk -> view.termProjections.get(chunk.id()))
                .mapToInt(KnowledgeTermProjection::tokenCount)
                .average()
                .orElse(1);
        Map<String, Long> documentFrequencies = queryTerms.stream().collect(Collectors.toMap(
                Function.identity(),
                term -> chunks.stream()
                        .map(chunk -> view.termProjections.get(chunk.id()))
                        .filter(projection -> projection.termFrequencies().containsKey(term))
                        .count()));
        ArrayList<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeTermProjection terms = view.termProjections.get(chunk.id());
            double score = 0;
            for (String term : queryTerms) {
                double tf = terms.termFrequencies().getOrDefault(term, 0);
                if (tf == 0) continue;
                double df = documentFrequencies.getOrDefault(term, 0L);
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                double denominator = tf + 1.2
                        * (1 - 0.75 + 0.75 * terms.tokenCount() / averageLength);
                score += idf * (tf * 2.2 / denominator);
            }
            if (score > 0) scored.add(new ScoredChunk(chunk, score));
        }
        return sort(scored);
    }

    private List<ScoredChunk> compatibilityBm25(String query, List<KnowledgeChunk> chunks) {
        List<String> queryTerms = tokens(query);
        if (queryTerms.isEmpty() || chunks.isEmpty()) return List.of();
        List<List<String>> documents = chunks.stream()
                .map(chunk -> tokens(chunk.content())).toList();
        double averageLength = documents.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Long> frequencies = queryTerms.stream().distinct().collect(Collectors.toMap(
                Function.identity(),
                term -> documents.stream().filter(tokens -> tokens.contains(term)).count()));
        ArrayList<ScoredChunk> scored = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            List<String> terms = documents.get(index);
            Map<String, Long> termFrequency = terms.stream().collect(Collectors.groupingBy(
                    Function.identity(), Collectors.counting()));
            double score = 0;
            for (String term : queryTerms) {
                double tf = termFrequency.getOrDefault(term, 0L);
                if (tf == 0) continue;
                double df = frequencies.getOrDefault(term, 0L);
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                double denominator = tf + 1.2 * (1 - 0.75 + 0.75 * terms.size() / averageLength);
                score += idf * (tf * 2.2 / denominator);
            }
            if (score > 0) scored.add(new ScoredChunk(chunks.get(index), score));
        }
        return sort(scored);
    }

    private List<ScoredChunk> vector(
            double[] queryVector, List<KnowledgeChunk> chunks, ProjectionView view) {
        ArrayList<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            double[] chunkVector = view.searchProjectionsAuthoritative
                    ? view.vectorProjections.get(chunk.id()).embedding().stream()
                            .mapToDouble(Double::doubleValue)
                            .toArray()
                    : vectorizer.vectorize(chunk.content());
            double score = cosine(queryVector, chunkVector);
            if (score > 0) scored.add(new ScoredChunk(chunk, score));
        }
        return sort(scored);
    }

    private static List<KnowledgeChunk> nativeRankedChunks(
            List<String> chunkIds,
            Map<String, KnowledgeChunk> authorized,
            String lane) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(chunkIds);
        if (unique.size() != chunkIds.size()) {
            throw new IllegalStateException(
                    "native " + lane + " ranking contains duplicate chunks");
        }
        ArrayList<KnowledgeChunk> chunks = new ArrayList<>(chunkIds.size());
        for (String chunkId : chunkIds) {
            KnowledgeChunk chunk = authorized.get(chunkId);
            if (chunk == null) {
                throw new SecurityException(
                        "native " + lane + " ranking escaped the frozen ACL/version scope");
            }
            chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    private static List<ScoredChunk> sort(List<ScoredChunk> values) {
        return values.stream().sorted(
                Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(value -> value.chunk.id())).toList();
    }

    private ProjectionView load(FrozenKnowledgeSnapshot snapshot) {
        return new ProjectionView(snapshot.loadAndValidate(projection));
    }

    private FrozenKnowledgeSnapshot snapshot(RetrievalContext context) {
        return FrozenKnowledgeSnapshot.restore(context.snapshotId());
    }

    private boolean validChunk(KnowledgeChunk chunk, ProjectionView view) {
        KnowledgeDocumentVersion version = view.versions.get(chunk.documentVersionId());
        return version != null
                && version.documentId().equals(chunk.documentId())
                && version.acl().equals(chunk.acl())
                && chunk.status() != com.meguri.core.knowledge.KnowledgeStatus.BUILDING
                && chunk.status() != com.meguri.core.knowledge.KnowledgeStatus.FAILED;
    }

    private boolean validEntity(
            KnowledgeEntity entity, ProjectionView view, RetrievalContext context) {
        KnowledgeDocumentVersion version = view.versions.get(entity.documentVersionId());
        KnowledgeChunk evidence = view.chunks.get(entity.evidenceChunkId());
        return version != null
                && version.documentId().equals(entity.documentId())
                && version.acl().equals(entity.acl())
                && entity.effectiveAt(context.validAt())
                && evidence != null
                && evidence.role() == ChunkRole.CHILD
                && evidence.documentId().equals(entity.documentId())
                && evidence.documentVersionId().equals(entity.documentVersionId())
                && evidence.acl().equals(entity.acl())
                && validChunk(evidence, view)
                && evidence.effectiveAt(context.validAt());
    }

    private boolean validRelation(
            KnowledgeRelation relation, ProjectionView view, RetrievalContext context) {
        KnowledgeDocumentVersion version = view.versions.get(relation.documentVersionId());
        KnowledgeEntity from = view.entities.get(relation.fromEntityId());
        KnowledgeEntity to = view.entities.get(relation.toEntityId());
        KnowledgeChunk evidence = view.chunks.get(relation.evidenceChunkId());
        return version != null
                && version.documentId().equals(relation.documentId())
                && version.acl().equals(relation.acl())
                && relation.effectiveAt(context.validAt())
                && from != null && to != null
                && from.documentVersionId().equals(relation.documentVersionId())
                && to.documentVersionId().equals(relation.documentVersionId())
                && validEntity(from, view, context)
                && validEntity(to, view, context)
                && evidence != null
                && evidence.role() == ChunkRole.CHILD
                && evidence.documentId().equals(relation.documentId())
                && evidence.documentVersionId().equals(relation.documentVersionId())
                && evidence.acl().equals(relation.acl())
                && validChunk(evidence, view)
                && evidence.effectiveAt(context.validAt());
    }

    private boolean visible(KnowledgeAcl acl, RetrievalContext context) {
        String tenantId = fixedTenantId == null ? context.tenantId() : fixedTenantId;
        if (!tenantId.equals(acl.tenantId())) return false;
        if (acl.principals().contains(context.principalId())) return true;
        return context.aclScopes().stream().anyMatch(acl.principals()::contains);
    }

    private static GraphPath toGraphPath(PathState state) {
        List<GraphPath.Edge> edges = state.edges.stream().map(relation ->
                new GraphPath.Edge(
                        relation.id(), relation.fromEntityId(), relation.relationType(),
                        relation.toEntityId(), List.of(relation.evidenceChunkId()),
                        relation.confidence()))
                .toList();
        double score = edges.stream().mapToDouble(GraphPath.Edge::score)
                .average().orElse(0);
        return new GraphPath(state.nodes, edges, score);
    }

    private static RetrievalCitation citation(
            KnowledgeChunk chunk, ProjectionView view) {
        KnowledgeDocument document = view.documents.get(chunk.documentId());
        if (document == null) return RetrievalCitation.empty();
        return RetrievalCitation.single(
                document.metadata().canonicalUri(), document.title(),
                chunk.documentVersionId(), chunk.id(),
                chunk.metadata().sourceStart(), chunk.metadata().sourceEnd());
    }

    private RestoredContext restoreContext(
            KnowledgeChunk parent, KnowledgeChunk child) {
        if (parent.metadata().tokenCount() <= parentContextTokenLimit) {
            return new RestoredContext(
                    parent.content(), parent.metadata().tokenCount(), List.of());
        }
        List<ContextParagraph> paragraphs = contextParagraphs(parent.content());
        int target = targetParagraph(parent, child, paragraphs);
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        selected.add(target);
        int tokens = paragraphs.get(target).tokenCount();
        boolean expanded;
        do {
            expanded = false;
            int minimum = selected.stream().mapToInt(Integer::intValue).min().orElse(target);
            int maximum = selected.stream().mapToInt(Integer::intValue).max().orElse(target);
            if (minimum > 0) {
                int candidate = paragraphs.get(minimum - 1).tokenCount();
                if (tokens + candidate <= parentContextTokenLimit) {
                    selected.add(minimum - 1);
                    tokens += candidate;
                    expanded = true;
                }
            }
            if (maximum + 1 < paragraphs.size()) {
                int candidate = paragraphs.get(maximum + 1).tokenCount();
                if (tokens + candidate <= parentContextTokenLimit) {
                    selected.add(maximum + 1);
                    tokens += candidate;
                    expanded = true;
                }
            }
        } while (expanded);
        String content = selected.stream().sorted()
                .map(index -> paragraphs.get(index).content())
                .collect(Collectors.joining("\n\n"));
        List<String> degradations = tokens > parentContextTokenLimit
                ? List.of("parent_context_child_exceeds_budget")
                : List.of("parent_context_trimmed");
        return new RestoredContext(content, estimateTokens(content), degradations);
    }

    private static int targetParagraph(
            KnowledgeChunk parent,
            KnowledgeChunk child,
            List<ContextParagraph> paragraphs) {
        Integer parentStart = parent.metadata().sourceStart();
        Integer childStart = child.metadata().sourceStart();
        if (parentStart != null && childStart != null) {
            int relative = childStart - parentStart;
            for (int index = 0; index < paragraphs.size(); index++) {
                ContextParagraph paragraph = paragraphs.get(index);
                if (paragraph.start() == relative) return index;
            }
        }
        for (int index = 0; index < paragraphs.size(); index++) {
            if (paragraphs.get(index).content().equals(child.content())) return index;
        }
        throw new IllegalStateException(
                "retrieved CHILD is not anchored in its PARENT");
    }

    private static List<ContextParagraph> contextParagraphs(String content) {
        ArrayList<ContextParagraph> result = new ArrayList<>();
        int searchFrom = 0;
        for (String raw : content.split("\\n\\s*\\n")) {
            String paragraph = raw.trim();
            if (paragraph.isBlank()) continue;
            int start = content.indexOf(paragraph, searchFrom);
            if (start < 0) {
                throw new IllegalStateException(
                        "parent context paragraph cannot be anchored");
            }
            int end = start + paragraph.length();
            result.add(new ContextParagraph(
                    paragraph, start, end, estimateTokens(paragraph)));
            searchFrom = end;
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("parent context is empty");
        }
        return List.copyOf(result);
    }

    private static int positiveTokenLimit(int value) {
        if (value < 1 || value > 32_768) {
            throw new IllegalArgumentException(
                    "parent context token limit must be 1..32768");
        }
        return value;
    }

    private record ContextParagraph(
            String content, int start, int end, int tokenCount) { }

    private record RestoredContext(
            String content, int tokenCount, List<String> degradations) { }

    private record RankedLane(
            List<KnowledgeChunk> chunks,
            List<String> degradations) {
        private RankedLane {
            chunks = List.copyOf(chunks);
            degradations = List.copyOf(degradations);
        }

        private static RankedLane success(List<KnowledgeChunk> chunks) {
            return new RankedLane(chunks, List.of());
        }

        private static RankedLane degraded(
                List<KnowledgeChunk> chunks, String degradation) {
            return new RankedLane(chunks, List.of(degradation));
        }
    }

    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        ArrayList<String> result = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{L}\\p{N}]+").matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            result.add(token);
            if (token.codePoints().allMatch(code -> Character.UnicodeScript.of(code)
                    == Character.UnicodeScript.HAN) && token.codePointCount(0, token.length()) > 1) {
                int[] points = token.codePoints().toArray();
                for (int index = 0; index < points.length - 1; index++) {
                    result.add(new String(points, index, 2));
                }
            }
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return String.join("", tokens(value));
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("knowledge vectors must use one dimension");
        }
        double score = 0;
        for (int index = 0; index < left.length; index++) score += left[index] * right[index];
        return score;
    }

    private static int estimateTokens(String content) {
        return Math.max(1, (content.codePointCount(0, content.length()) + 3) / 4);
    }

    private static <T> List<T> concat(List<T> left, List<T> right) {
        ArrayList<T> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score) {
    }

    private record PathState(List<String> nodes, List<KnowledgeRelation> edges) {
    }

    private static final class ProjectionView {
        private final Map<String, KnowledgeDocument> documents;
        private final Map<String, KnowledgeDocumentVersion> versions;
        private final Map<String, KnowledgeChunk> chunks;
        private final Map<String, KnowledgeEntity> entities;
        private final Map<String, KnowledgeRelation> relations;
        private final Map<String, KnowledgeTermProjection> termProjections;
        private final Map<String, KnowledgeVectorProjection> vectorProjections;
        private final boolean searchProjectionsAuthoritative;

        private ProjectionView(KnowledgeSearchProjectionPort.Projection projection) {
            documents = unique(projection.documents(), KnowledgeDocument::id);
            versions = unique(projection.versions(), KnowledgeDocumentVersion::id);
            chunks = unique(projection.chunks(), KnowledgeChunk::id);
            entities = unique(projection.entities(), KnowledgeEntity::id);
            relations = unique(projection.relations(), KnowledgeRelation::id);
            termProjections = unique(
                    projection.termProjections(), KnowledgeTermProjection::chunkId);
            vectorProjections = unique(
                    projection.vectorProjections(), KnowledgeVectorProjection::chunkId);
            searchProjectionsAuthoritative = projection.searchProjectionsAuthoritative();
            if (searchProjectionsAuthoritative) validateSearchProjections();
        }

        private void validateSearchProjections() {
            Set<String> childIds = chunks.values().stream()
                    .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                    .map(KnowledgeChunk::id)
                    .collect(Collectors.toUnmodifiableSet());
            if (!termProjections.keySet().equals(childIds)
                    || !vectorProjections.keySet().equals(childIds)) {
                throw new IllegalStateException(
                        "authoritative knowledge projection is incomplete");
            }
            for (String chunkId : childIds) {
                KnowledgeChunk chunk = chunks.get(chunkId);
                KnowledgeTermProjection terms = termProjections.get(chunkId);
                KnowledgeVectorProjection vector = vectorProjections.get(chunkId);
                if (!matches(chunk, terms.documentId(), terms.documentVersionId(), terms.acl())
                        || !matches(
                                chunk, vector.documentId(),
                                vector.documentVersionId(), vector.acl())) {
                    throw new IllegalStateException(
                            "knowledge search projection metadata mismatch");
                }
                if (!DeterministicSearchProjector.EMBEDDING_MODEL.equals(
                                vector.embeddingModel())
                        || vector.dimensions()
                                != DeterministicSearchProjector.EMBEDDING_DIMENSIONS) {
                    throw new IllegalStateException(
                            "knowledge vector projection model mismatch");
                }
            }
        }

        private static boolean matches(
                KnowledgeChunk chunk,
                String documentId,
                String documentVersionId,
                KnowledgeAcl acl) {
            return chunk.documentId().equals(documentId)
                    && chunk.documentVersionId().equals(documentVersionId)
                    && chunk.acl().equals(acl);
        }

        private static <T> Map<String, T> unique(
                List<T> values, Function<T, String> identifier) {
            try {
                return values.stream().collect(Collectors.toUnmodifiableMap(identifier, value -> value));
            } catch (IllegalStateException duplicate) {
                throw new IllegalStateException("knowledge projection contains duplicate ids", duplicate);
            }
        }
    }
}
