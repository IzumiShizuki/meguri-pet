package com.meguri.core.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("External Skill PostgreSQL schema")
class SkillRuntimeSqlContractTest {
    @Test
    void should_persist_catalog_revisions_bindings_and_metadata_only_audit() throws Exception {
        String sql = new ClassPathResource("db/skill-runtime.sql")
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql).contains("create table if not exists external_skill")
                .contains("unique (source_id, external_id)")
                .contains("create table if not exists external_skill_revision")
                .contains("primary key (skill_id, digest)")
                .contains("create table if not exists external_skill_binding")
                .contains("state in ('disabled', 'enabled')")
                .contains("create table if not exists external_skill_audit")
                .contains("failure_code text")
                .doesNotContain("body text")
                .doesNotContain("content text")
                .doesNotContain("secret_value");
    }
}
