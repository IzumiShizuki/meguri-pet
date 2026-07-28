package com.meguri.core.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared deterministic ranking used by both authority adapters. */
final class KnowledgeSearchRanking {
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;

    private KnowledgeSearchRanking() {
    }

    static List<KnowledgeSearchHit> keyword(
            List<KnowledgeChunk> chunks,
            List<KnowledgeTermProjection> projections,
            String query,
            int limit) {
        if (limit <= 0 || chunks.isEmpty()) return List.of();
        Set<String> queryTerms = DeterministicSearchProjector.termFrequencies(query).keySet();
        if (queryTerms.isEmpty()) return List.of();
        Map<String, KnowledgeTermProjection> byChunk = projections.stream()
                .collect(Collectors.toMap(KnowledgeTermProjection::chunkId, Function.identity()));
        double averageLength = chunks.stream()
                .map(byChunk::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(KnowledgeTermProjection::tokenCount)
                .average().orElse(1.0);
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String term : queryTerms) {
            int count = (int) chunks.stream()
                    .map(byChunk::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(projection -> projection.termFrequencies().containsKey(term))
                    .count();
            documentFrequency.put(term, count);
        }

        ArrayList<KnowledgeSearchHit> hits = new ArrayList<>();
        int corpusSize = chunks.size();
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeTermProjection projection = byChunk.get(chunk.id());
            if (projection == null) continue;
            double score = 0.0;
            for (String term : queryTerms) {
                int frequency = projection.termFrequencies().getOrDefault(term, 0);
                if (frequency == 0) continue;
                int documentsWithTerm = documentFrequency.get(term);
                double inverseDocumentFrequency = Math.log(
                        1.0 + (corpusSize - documentsWithTerm + 0.5) / (documentsWithTerm + 0.5));
                double lengthNormalization = BM25_K1 * (
                        1.0 - BM25_B + BM25_B * projection.tokenCount() / averageLength);
                score += inverseDocumentFrequency
                        * frequency * (BM25_K1 + 1.0) / (frequency + lengthNormalization);
            }
            if (score > 0.0) hits.add(new KnowledgeSearchHit(chunk, score));
        }
        return top(hits, limit);
    }

    static List<KnowledgeSearchHit> vector(
            List<KnowledgeChunk> chunks,
            List<KnowledgeVectorProjection> projections,
            List<Double> queryEmbedding,
            int limit) {
        if (limit <= 0 || chunks.isEmpty()) return List.of();
        List<Double> normalizedQuery = normalize(queryEmbedding);
        Map<String, KnowledgeVectorProjection> byChunk = projections.stream()
                .collect(Collectors.toMap(KnowledgeVectorProjection::chunkId, Function.identity()));
        ArrayList<KnowledgeSearchHit> hits = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeVectorProjection projection = byChunk.get(chunk.id());
            if (projection == null || projection.dimensions() != normalizedQuery.size()) continue;
            double score = 0.0;
            for (int index = 0; index < normalizedQuery.size(); index++) {
                score += normalizedQuery.get(index) * projection.embedding().get(index);
            }
            hits.add(new KnowledgeSearchHit(chunk, score));
        }
        return top(hits, limit);
    }

    private static List<KnowledgeSearchHit> top(List<KnowledgeSearchHit> hits, int limit) {
        return hits.stream()
                .sorted(Comparator.comparingDouble(KnowledgeSearchHit::score).reversed()
                        .thenComparing(hit -> hit.chunk().id()))
                .limit(limit)
                .toList();
    }

    private static List<Double> normalize(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalArgumentException("query embedding must not be empty");
        }
        if (embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("query embedding values must be finite");
        }
        double norm = Math.sqrt(embedding.stream().mapToDouble(value -> value * value).sum());
        if (norm == 0.0) throw new IllegalArgumentException("query embedding norm is zero");
        return embedding.stream().map(value -> value / norm).toList();
    }
}
