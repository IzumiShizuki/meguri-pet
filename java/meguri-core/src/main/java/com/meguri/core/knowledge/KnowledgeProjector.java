package com.meguri.core.knowledge;

@FunctionalInterface
public interface KnowledgeProjector {
    KnowledgeBuild project(KnowledgeDocument document,
                           KnowledgeDocumentVersion version,
                           SourcePage page);
}
