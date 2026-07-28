package com.meguri.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeEvidenceValidatorTest {
    private static final Instant NOW = Instant.parse("2026-07-28T08:00:00Z");
    private static final KnowledgeAcl ACL = new KnowledgeAcl("meguri", Set.of("user:izumi"));

    @Test
    void independentlyRejectsAclMismatchAndMissingEvidence() {
        KnowledgeDocument document = new KnowledgeDocument(
                "document", "notion", "page", "Page", ACL,
                KnowledgeStatus.BUILDING, NOW, NOW, null);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(
                "version", document.id(), 1, "a".repeat(64), ACL,
                KnowledgeStatus.BUILDING, NOW, NOW, null, null, null, null);
        KnowledgeChunk parent = KnowledgeChunk.parent(
                "parent", document.id(), version.id(), 0, "context", ACL, NOW);
        KnowledgeChunk child = KnowledgeChunk.child(
                "child", document.id(), version.id(), parent.id(), 1, "evidence", ACL, NOW);
        KnowledgeEvidenceValidator validator = new KnowledgeEvidenceValidator();

        KnowledgeEntity wrongAcl = new KnowledgeEntity(
                "entity-acl", document.id(), version.id(), "system", "Meguri", child.id(),
                new KnowledgeAcl("meguri", Set.of("user:other")), NOW);
        assertThatThrownBy(() -> validator.validate(
                document, version, new KnowledgeBuild(
                        List.of(parent, child), List.of(wrongAcl), List.of())))
                .hasMessageContaining("ACL");

        KnowledgeEntity missingEvidence = new KnowledgeEntity(
                "entity-evidence", document.id(), version.id(), "system", "Meguri",
                "missing", ACL, NOW);
        assertThatThrownBy(() -> validator.validate(
                document, version, new KnowledgeBuild(
                        List.of(parent, child), List.of(missingEvidence), List.of())))
                .hasMessageContaining("evidence");
    }
}
