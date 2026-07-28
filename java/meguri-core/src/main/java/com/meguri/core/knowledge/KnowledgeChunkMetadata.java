package com.meguri.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Immutable source anchor and integrity metadata for a parent or child chunk. */
public record KnowledgeChunkMetadata(
        List<String> sectionPath,
        String heading,
        Integer sourceStart,
        Integer sourceEnd,
        String contentHash,
        int tokenCount) {

    public KnowledgeChunkMetadata {
        sectionPath = List.copyOf(sectionPath == null ? List.of() : sectionPath);
        heading = heading == null || heading.isBlank() ? null : heading.trim();
        if ((sourceStart == null) != (sourceEnd == null)) {
            throw new IllegalArgumentException(
                    "sourceStart and sourceEnd must both be present or absent");
        }
        if (sourceStart != null && (sourceStart < 0 || sourceEnd <= sourceStart)) {
            throw new IllegalArgumentException("source offsets are invalid");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "contentHash must be a lowercase SHA-256 digest");
        }
        if (tokenCount < 1) {
            throw new IllegalArgumentException("tokenCount must be positive");
        }
    }

    public static KnowledgeChunkMetadata derive(String content) {
        String value = content == null ? "" : content;
        return new KnowledgeChunkMetadata(
                List.of(), heading(value), null, null,
                sha256(value), estimateTokens(value));
    }

    public static KnowledgeChunkMetadata anchored(
            String content,
            List<String> sectionPath,
            String heading,
            int sourceStart,
            int sourceEnd) {
        return new KnowledgeChunkMetadata(
                sectionPath, heading, sourceStart, sourceEnd,
                sha256(content), estimateTokens(content));
    }

    private static String heading(String content) {
        String first = content.lines().findFirst().orElse("").strip();
        if (!first.startsWith("#")) return null;
        String value = first.replaceFirst("^#+\\s*", "").strip();
        return value.isBlank() ? null : value;
    }

    private static int estimateTokens(String content) {
        int projected = DeterministicSearchProjector.termFrequencies(content)
                .values().stream().mapToInt(Integer::intValue).sum();
        return Math.max(1, projected);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
