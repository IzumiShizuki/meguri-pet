package com.meguri.core.knowledge;

public record BuildStart(
        KnowledgeDocument document,
        KnowledgeDocumentVersion version,
        boolean skipped) {
}
