package com.meguri.core.context;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRuntimeSqlContractTest {
    @Test
    void schemaContainsDurableTraceTopicAndIdempotentJobTables() throws Exception {
        String sql = new ClassPathResource("db/context-runtime.sql")
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql).contains("create table if not exists context_build_trace")
                .contains("bundle_json jsonb not null")
                .contains("create table if not exists context_topic_segment")
                .contains("create table if not exists context_precompression_job")
                .contains("idempotency_key varchar(512) not null unique")
                .contains("lease_until timestamptz");
    }
}
