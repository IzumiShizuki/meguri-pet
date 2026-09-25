package com.meguri.core.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class LegacyRetrievalItems {
    private LegacyRetrievalItems() {}

    static RetrievalItem item(
            SourceType source, String content, int rank, double trust,
            String version, RetrievalContext context) {
        String id = sha256(source + "\n" + content);
        String uri = "meguri://" + source.name().toLowerCase(java.util.Locale.ROOT) + "/" + id;
        return new RetrievalItem(
                source, id, content,
                RetrievalCitation.single(uri, source.name(), version, id, null, null),
                trust, new RankTrace(java.util.Map.of(RankSignal.STRUCTURED, rank), 0),
                estimateTokens(content), context.validAt(), null, List.of(), List.of(),
                context.traceId());
    }

    static int estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(value.codePointCount(0, value.length()) / 3.0));
    }

    static String version(RetrievalContext context) {
        return context.snapshotId() + ":" + context.revision();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
