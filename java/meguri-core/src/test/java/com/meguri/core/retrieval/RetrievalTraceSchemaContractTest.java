package com.meguri.core.retrieval;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalTraceSchemaContractTest {
    @Test
    void postgresSchemaKeepsOneImmutableContentBearingTracePerTurnTraceId()
            throws Exception {
        String sql;
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("db/retrieval-runtime.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
        }

        assertThat(sql)
                .contains("trace_id text primary key")
                .contains("snapshot_id text not null")
                .contains("knowledge_revision bigint not null")
                .contains("valid_at timestamptz not null")
                .contains("algorithm_revision text not null")
                .contains("trace_json jsonb not null");
    }
}
