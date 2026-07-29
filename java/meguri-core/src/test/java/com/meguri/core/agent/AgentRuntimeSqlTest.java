package com.meguri.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeSqlTest {
    @Test
    void schemaDefinesDurableStatesIdempotencyAndResumeIndexes() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/agent-runtime.sql")) {
            assertThat(stream).isNotNull();
            bytes = stream.readAllBytes();
        }
        String sql = new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertThat(sql)
                .contains("create table if not exists skill_execution")
                .contains("create table if not exists skill_step_execution")
                .contains("create table if not exists agent_task")
                .contains("capability_snapshot_version")
                .contains("uq_agent_task_idempotency")
                .contains("payload_hash char(64)")
                .contains("waiting_remote_agent")
                .contains("waiting_external")
                .contains("ix_agent_task_resumable")
                .contains("where status in ('created', 'queued', 'running', 'waiting_external')")
                .contains("ix_agent_task_parent_budget")
                .contains("ix_agent_task_quota");
    }
}
