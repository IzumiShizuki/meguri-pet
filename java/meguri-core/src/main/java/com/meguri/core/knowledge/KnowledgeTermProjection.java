package com.meguri.core.knowledge;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Persistable term-frequency projection used to compute BM25 at query time. */
public record KnowledgeTermProjection(
        String chunkId,
        String documentId,
        String documentVersionId,
        KnowledgeAcl acl,
        Map<String, Integer> termFrequencies,
        int tokenCount) {
    public KnowledgeTermProjection {
        chunkId = required(chunkId, "chunkId");
        documentId = required(documentId, "documentId");
        documentVersionId = required(documentVersionId, "documentVersionId");
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(termFrequencies, "termFrequencies");
        TreeMap<String, Integer> normalized = new TreeMap<>();
        int total = 0;
        for (Map.Entry<String, Integer> entry : termFrequencies.entrySet()) {
            String term = required(entry.getKey(), "term");
            Integer frequency = Objects.requireNonNull(entry.getValue(), "term frequency");
            if (frequency < 1) throw new IllegalArgumentException("term frequency must be positive");
            normalized.put(term, frequency);
            total += frequency;
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("term projection must not be empty");
        if (tokenCount != total) throw new IllegalArgumentException("tokenCount must equal term frequencies");
        termFrequencies = Collections.unmodifiableMap(normalized);
    }

    public String normalizedTerms() {
        StringBuilder result = new StringBuilder();
        termFrequencies.forEach((term, frequency) -> {
            for (int index = 0; index < frequency; index++) {
                if (!result.isEmpty()) result.append(' ');
                result.append(term);
            }
        });
        return result.toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
