package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Minimal online graph storage port; no dependency on a concrete knowledge implementation. */
public interface KnowledgeGraphStorePort {
    List<GraphPath> findPaths(
            List<String> normalizedEntityIds, int maxHops, int limit, RetrievalContext context);

    Map<String, EvidenceChunk> expandEvidence(
            Collection<String> chunkIds, RetrievalContext context);

    record EvidenceChunk(
            String chunkId,
            String content,
            RetrievalCitation citation,
            double trust,
            int tokenCount,
            Instant validAt) {
        public EvidenceChunk {
            if (chunkId == null || chunkId.isBlank()
                    || content == null || content.isBlank()) {
                throw new IllegalArgumentException("evidence chunk id and content are required");
            }
            citation = citation == null ? RetrievalCitation.empty() : citation;
            if (!Double.isFinite(trust) || trust < 0 || trust > 1 || tokenCount < 0) {
                throw new IllegalArgumentException("invalid evidence trust or token count");
            }
            validAt = validAt == null ? Instant.EPOCH : validAt;
        }

        public EvidenceChunk(
                String chunkId,
                String content,
                String citation,
                double trust,
                int tokenCount,
                Instant validAt) {
            this(chunkId, content, RetrievalCitation.external(citation), trust,
                    tokenCount, validAt);
        }
    }
}
