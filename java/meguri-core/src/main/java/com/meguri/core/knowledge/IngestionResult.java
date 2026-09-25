package com.meguri.core.knowledge;

public record IngestionResult(
        String sourceId,
        String pageId,
        IngestionOutcome outcome,
        String documentId,
        String versionId,
        String detail) {
}
