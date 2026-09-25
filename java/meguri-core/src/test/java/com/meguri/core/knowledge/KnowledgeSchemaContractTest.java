package com.meguri.core.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSchemaContractTest {
    @Test
    void schemaEnforcesVersionAclEvidenceAndSingleActivePublication() throws IOException {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/knowledge-runtime.sql")) {
            assertThat(stream).isNotNull();
            bytes = stream.readAllBytes();
        }
        String sql = new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("create table if not exists knowledge_document")
                .contains("create table if not exists knowledge_document_version")
                .contains("create table if not exists knowledge_chunk")
                .contains("create table if not exists knowledge_entity")
                .contains("create table if not exists knowledge_relation")
                .contains("create extension if not exists vector")
                .contains("create extension if not exists pgcrypto")
                .contains("source_uri text")
                .contains("canonical_uri text")
                .contains("linked_document_ids jsonb")
                .contains("current_active_version_id varchar(128)")
                .contains("fk_knowledge_document_current_active_version")
                .contains("parser_revision")
                .contains("chunker_revision")
                .contains("embedding_revision")
                .contains("section_path jsonb")
                .contains("source_start integer")
                .contains("source_end integer")
                .contains("content_sha256 char(64)")
                .contains("ck_knowledge_chunk_source_offsets")
                .contains("term_frequencies_json")
                .contains("search_vector tsvector")
                .contains("using gin (search_vector)")
                .contains("embedding vector(64)")
                .contains("embedding_revision varchar(128) not null")
                .contains("ck_knowledge_chunk_embedding_revision")
                .contains("chunk_role = 'parent' and embedding_revision is null")
                .contains("chunk_role = 'child' and embedding_revision is not null")
                .contains("using hnsw (embedding vector_cosine_ops)")
                .contains("aliases jsonb")
                .contains("confidence double precision")
                .contains("valid_from timestamptz")
                .contains("valid_until timestamptz")
                .contains("uq_knowledge_chunk_projection_scope")
                .contains("uq_knowledge_version_active")
                .contains("where status = 'active'")
                .contains("parent_chunk_id")
                .contains("evidence_chunk_id")
                .contains("foreign key (evidence_chunk_id, document_version_id, acl_hash")
                .contains("check (status in ('building', 'active', 'superseded', 'failed', 'deleted'))")
                .contains("check ((chunk_role = 'parent' and parent_chunk_id is null)")
                .contains("ix_knowledge_chunk_recall")
                .contains("where chunk_role = 'child'");
        assertThat(sql)
                .doesNotContain("create table if not exists knowledge_chunk_term_projection")
                .doesNotContain("create table if not exists knowledge_chunk_vector_projection");
    }
}
