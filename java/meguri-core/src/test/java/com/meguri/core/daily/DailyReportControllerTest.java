package com.meguri.core.daily;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

class DailyReportControllerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesLatestMetadataAndServesTheArchivedMarkdown() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WebTestClient client = WebTestClient.bindToController(
                new DailyReportController(new DailyReportStore(mapper, temporaryDirectory))).build();
        String markdown = "# 2026-07-24 fixture 日报\n";
        Map<String, Object> upload = fixture(markdown);

        client.post().uri("/v1/daily/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upload)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.report_id").isEqualTo("bilibili:2026-07-24")
                .jsonPath("$.markdown_href")
                .isEqualTo("/v1/daily/reports/bilibili/2026-07-24/markdown");

        client.get().uri("/v1/daily/reports/latest?kind=bilibili")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.delivery_text").isEqualTo("【中文】\n【日本語】")
                .jsonPath("$.markdown").doesNotExist();

        client.get().uri("/v1/daily/reports/bilibili/2026-07-24/markdown")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/markdown;charset=UTF-8")
                .expectBody(String.class).isEqualTo(markdown);
    }

    @Test
    void rejectsAHashThatDoesNotMatchTheMarkdown() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WebTestClient client = WebTestClient.bindToController(
                new DailyReportController(new DailyReportStore(mapper, temporaryDirectory))).build();
        Map<String, Object> upload = fixture("# fixture\n");
        upload.put("markdown_sha256", "0".repeat(64));

        client.post().uri("/v1/daily/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upload)
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void returnsNotFoundBeforeAnyReportIsUploaded() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WebTestClient client = WebTestClient.bindToController(
                new DailyReportController(new DailyReportStore(mapper, temporaryDirectory))).build();

        client.get().uri("/v1/daily/reports/latest?kind=bilibili")
                .exchange()
                .expectStatus().isNotFound();
    }

    private static Map<String, Object> fixture(String markdown) {
        return new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("schema_version", 1),
                Map.entry("report_id", "bilibili:2026-07-24"),
                Map.entry("kind", "bilibili"),
                Map.entry("date", "2026-07-24"),
                Map.entry("title", "fixture report"),
                Map.entry("summary", "fixture summary"),
                Map.entry("delivery_text", "【中文】\n【日本語】"),
                Map.entry("delivery_speech_text", "中文"),
                Map.entry("generated_at", "2026-07-25T02:36:47+08:00"),
                Map.entry("published_at", "2026-07-25T02:37:00+08:00"),
                Map.entry("data_source", "account_mcp"),
                Map.entry("sync_status", "success"),
                Map.entry("unique_videos", 64),
                Map.entry("total_visits", 64),
                Map.entry("markdown_sha256", sha256(markdown)),
                Map.entry("markdown", markdown)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
