package com.meguri.core.memory.job;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PostReplyMemoryJobSqlContractTest {
    @Test
    void schemaCarriesIdempotencyLeaseAndDeadLetterContract() throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/post-reply-memory-job.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains("unique (turn_id, response_digest)")
                    .contains("owner_id")
                    .contains("lease_until")
                    .contains("dead_letter")
                    .contains("cancelled_after_reply");
        }
    }
}
