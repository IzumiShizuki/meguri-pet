package com.meguri.core.resource;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsEverythingSearchGatewayTest {
    @Test
    void parsesUtf8CsvIncludingQuotedCommasAndNewlines() {
        String csv = "\ufeff\"D:\\资料\\日报,七月.md\",123,2026-07-22T08:00:00Z\r\n"
                + "\"D:\\资料\\两行\r\n文件.txt\",8,2026-07-21T01:02:03Z\r\n";

        List<EverythingSearchHit> hits = EsEverythingSearchGateway.parseCsv(csv);

        assertThat(hits).containsExactly(
                new EverythingSearchHit("D:\\资料\\日报,七月.md", 123L, Instant.parse("2026-07-22T08:00:00Z")),
                new EverythingSearchHit("D:\\资料\\两行\r\n文件.txt", 8L, Instant.parse("2026-07-21T01:02:03Z"))
        );
    }
}
