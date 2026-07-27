package com.meguri.core.bilibili;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliBrowserReportControllerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsStatsBoundaryAndClickableMarkdownArtifact() {
        LocalDate[] observedDate = new LocalDate[1];
        BilibiliBrowserReportGateway gateway = date -> {
            observedDate[0] = date;
            return Mono.just(sample(date));
        };
        WebTestClient client = WebTestClient.bindToController(
                        new BilibiliBrowserReportController(gateway, temporaryDirectory))
                .configureClient().baseUrl("http://localhost").build();

        client.get().uri("/v1/daily/bilibili/briefing?date=2026-07-22")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ready")
                .jsonPath("$.unique_videos").isEqualTo(1)
                .jsonPath("$.total_visits").isEqualTo(2)
                .jsonPath("$.boundary").value(value -> assertThat(String.valueOf(value))
                        .contains("页面访问不代表视频已播放、看完"))
                .jsonPath("$.artifacts[0].href")
                .isEqualTo("http://localhost/v1/daily/bilibili/report?date=2026-07-22");

        assertThat(observedDate[0]).isEqualTo(LocalDate.of(2026, 7, 22));
    }

    @Test
    void servesOnlyTheDateNamedGeneratedMarkdown() throws Exception {
        Path reports = temporaryDirectory.resolve("reports/daily");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("bilibili-2026-07-22.md"), "# fixture 日报");
        BilibiliBrowserReportGateway gateway = ignored -> Mono.just(sample(ignored));
        WebTestClient client = WebTestClient.bindToController(
                new BilibiliBrowserReportController(gateway, temporaryDirectory)).build();

        client.get().uri("/v1/daily/bilibili/report?date=2026-07-22")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/markdown;charset=UTF-8")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(String.class).isEqualTo("# fixture 日报");

        client.get().uri("/v1/daily/bilibili/report?date=2026-07-21")
                .exchange()
                .expectStatus().isNotFound();
    }

    private BilibiliBrowserBriefing sample(LocalDate date) {
        String value = String.valueOf(date);
        return new BilibiliBrowserBriefing(
                "ready", value, "2026-07-22T20:00:00+08:00",
                "browser_history", "account_mcp_not_configured", 1, 2,
                "2026-07-22T09:00:00+08:00", "2026-07-22T10:00:00+08:00", "fixture summary",
                List.of(new BilibiliBrowserVideo(
                        "BV1xx411c7mD", "fixture", "https://www.bilibili.com/video/BV1xx411c7mD/",
                        2, "2026-07-22T09:00:00+08:00", "2026-07-22T10:00:00+08:00", List.of("Chrome/Test"))),
                List.of(new BilibiliBrowserSource("Chrome/Test", "ready", 2, null)),
                BilibiliBrowserBriefing.PRIVACY_BOUNDARY,
                List.of(new BilibiliBrowserArtifact(
                        "Bilibili 浏览器访问日报（Markdown）",
                        "/v1/daily/bilibili/report?date=" + value,
                        temporaryDirectory.resolve("reports/daily/bilibili-" + value + ".md").toString())),
                null);
    }
}
