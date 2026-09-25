package com.meguri.core.retrieval;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Structured provenance anchors retained across ranking, generation and replay. */
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(
        using = RetrievalCitation.Deserializer.class)
public record RetrievalCitation(List<Anchor> anchors) {

    public RetrievalCitation {
        LinkedHashSet<Anchor> unique = new LinkedHashSet<>();
        if (anchors != null) {
            for (Anchor anchor : anchors) {
                if (anchor == null) continue;
                unique.add(anchor);
            }
        }
        anchors = List.copyOf(unique);
    }

    public static RetrievalCitation empty() {
        return new RetrievalCitation(List.of());
    }

    public static RetrievalCitation external(String canonicalUri) {
        if (canonicalUri == null || canonicalUri.isBlank()) return empty();
        return single(canonicalUri, "", "", "", null, null);
    }

    public static RetrievalCitation single(
            String canonicalUri,
            String title,
            String documentVersionId,
            String chunkId,
            Integer sourceStart,
            Integer sourceEnd) {
        return new RetrievalCitation(List.of(new Anchor(
                canonicalUri, title, documentVersionId, chunkId,
                sourceStart, sourceEnd)));
    }

    public static RetrievalCitation merge(Collection<RetrievalCitation> citations) {
        if (citations == null) return empty();
        return new RetrievalCitation(citations.stream()
                .filter(value -> value != null)
                .flatMap(value -> value.anchors().stream())
                .toList());
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return anchors.isEmpty();
    }

    public String displayReferences() {
        return anchors.stream().map(Anchor::displayReference)
                .distinct().collect(java.util.stream.Collectors.joining("; "));
    }

    public record Anchor(
            String canonicalUri,
            String title,
            String documentVersionId,
            String chunkId,
            Integer sourceStart,
            Integer sourceEnd) {

        public Anchor {
            canonicalUri = required(canonicalUri, "canonicalUri");
            title = optional(title);
            documentVersionId = optional(documentVersionId);
            chunkId = optional(chunkId);
            if ((sourceStart == null) != (sourceEnd == null)) {
                throw new IllegalArgumentException(
                        "citation sourceStart and sourceEnd must both be present or absent");
            }
            if (sourceStart != null && (sourceStart < 0 || sourceEnd <= sourceStart)) {
                throw new IllegalArgumentException("citation source offsets are invalid");
            }
        }

        private String displayReference() {
            StringBuilder value = new StringBuilder(canonicalUri);
            if (!title.isBlank()) value.append(" (").append(title).append(')');
            if (!documentVersionId.isBlank()) {
                value.append(" version=").append(documentVersionId);
            }
            if (!chunkId.isBlank()) value.append(" chunk=").append(chunkId);
            if (sourceStart != null) {
                value.append(" offset=").append(sourceStart).append('-').append(sourceEnd);
            }
            return value.toString();
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }

        private static String optional(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Accepts both the structured v2 object and legacy string citations. */
    public static final class Deserializer
            extends com.fasterxml.jackson.databind.JsonDeserializer<RetrievalCitation> {
        @Override
        public RetrievalCitation deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode value = parser.readValueAsTree();
            if (value == null || value.isNull()) return RetrievalCitation.empty();
            if (value.isTextual()) return RetrievalCitation.external(value.asText());
            com.fasterxml.jackson.databind.JsonNode anchors = value.get("anchors");
            if (anchors == null || !anchors.isArray()) {
                return (RetrievalCitation) context.handleUnexpectedToken(
                        RetrievalCitation.class, parser);
            }
            java.util.ArrayList<Anchor> parsed = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode anchor : anchors) {
                parsed.add(new Anchor(
                        requiredText(anchor, "canonicalUri"),
                        optionalText(anchor, "title"),
                        optionalText(anchor, "documentVersionId"),
                        optionalText(anchor, "chunkId"),
                        optionalInteger(anchor, "sourceStart"),
                        optionalInteger(anchor, "sourceEnd")));
            }
            return new RetrievalCitation(parsed);
        }

        private static String requiredText(
                com.fasterxml.jackson.databind.JsonNode node, String field) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.asText();
        }

        private static String optionalText(
                com.fasterxml.jackson.databind.JsonNode node, String field) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(field);
            return value == null || value.isNull() ? "" : value.asText("");
        }

        private static Integer optionalInteger(
                com.fasterxml.jackson.databind.JsonNode node, String field) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.intValue();
        }
    }
}
