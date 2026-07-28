package com.meguri.core.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Stable source provenance copied onto the knowledge document authority. */
public record KnowledgeSourceMetadata(
        String sourceUri,
        String canonicalUri,
        String language,
        String projectId,
        String sourceParentId,
        List<String> linkedDocumentIds) {

    public KnowledgeSourceMetadata {
        sourceUri = required(sourceUri, "sourceUri");
        canonicalUri = required(canonicalUri, "canonicalUri");
        language = required(language, "language").toLowerCase(Locale.ROOT);
        projectId = required(projectId, "projectId");
        sourceParentId = optional(sourceParentId);
        LinkedHashSet<String> links = new LinkedHashSet<>();
        if (linkedDocumentIds != null) {
            linkedDocumentIds.stream()
                    .map(value -> required(value, "linkedDocumentId"))
                    .sorted()
                    .forEach(links::add);
        }
        linkedDocumentIds = List.copyOf(links);
    }

    public static KnowledgeSourceMetadata defaults(
            String sourceId, String pageId, String content) {
        String stableUri = required(sourceId, "sourceId")
                + "://" + required(pageId, "pageId");
        return new KnowledgeSourceMetadata(
                stableUri, stableUri, detectLanguage(content),
                sourceId, null, List.of());
    }

    static String detectLanguage(String content) {
        String value = content == null ? "" : content;
        boolean han = value.codePoints().anyMatch(point ->
                Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
        boolean latin = value.codePoints().anyMatch(point ->
                Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN);
        if (han && latin) return "mul";
        if (han) return "zh";
        if (latin) return "en";
        return "und";
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
