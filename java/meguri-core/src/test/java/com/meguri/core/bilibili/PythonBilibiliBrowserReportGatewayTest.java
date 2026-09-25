package com.meguri.core.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PythonBilibiliBrowserReportGatewayTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledGatewayDoesNotStartPythonOrReadBrowserData() {
        PythonBilibiliBrowserReportGateway gateway = new PythonBilibiliBrowserReportGateway(
                new ObjectMapper(), false, "missing-python", temporaryDirectory.resolve("missing.py"),
                temporaryDirectory, ZoneId.of("Asia/Shanghai"), 1, "", "");

        BilibiliBrowserBriefing result = gateway.generate(LocalDate.of(2026, 7, 22)).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.error()).contains("未启用");
        assertThat(result.boundary()).contains("无法推断实际观看时长");
    }

    @Test
    void omittedDateUsesPreviousCompletedCalendarDay() {
        ZoneId timezone = ZoneId.of("Asia/Shanghai");
        LocalDate before = LocalDate.now(timezone).minusDays(1);
        PythonBilibiliBrowserReportGateway gateway = new PythonBilibiliBrowserReportGateway(
                new ObjectMapper(), false, "missing-python", temporaryDirectory.resolve("missing.py"),
                temporaryDirectory, timezone, 1, "", "");

        BilibiliBrowserBriefing result = gateway.generate(null).block();
        LocalDate after = LocalDate.now(timezone).minusDays(1);

        assertThat(result).isNotNull();
        assertThat(result.date()).isIn(before.toString(), after.toString());
    }

    @Test
    void accountMcpResultKeepsOnlyServerSideBoundaryAndSafeArtifact() throws Exception {
        Path report = temporaryDirectory.resolve("reports/daily/bilibili-2026-07-22.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "# fixture");
        PythonBilibiliBrowserReportGateway gateway = new PythonBilibiliBrowserReportGateway(
                new ObjectMapper(), true, "python", temporaryDirectory.resolve("script.py"),
                temporaryDirectory, ZoneId.of("Asia/Shanghai"), 20, "", "");
        BilibiliBrowserBriefing parsed = new BilibiliBrowserBriefing(
                "ready", "2026-07-22", "2026-07-22T20:00:00+08:00",
                "account_mcp", "success", 1, 1,
                "2026-07-22T09:00:00+08:00", "2026-07-22T09:00:00+08:00", "fixture",
                List.of(new BilibiliBrowserVideo(
                        "BV1xx411c7mD", "fixture", "https://www.bilibili.com/video/BV1xx411c7mD/",
                        1, "2026-07-22T09:00:00+08:00", "2026-07-22T09:00:00+08:00", List.of("MCP"))),
                List.of(new BilibiliBrowserSource("MCP", "account_mcp", "ready", 1, null)),
                "attacker supplied boundary", List.of(), null);

        BilibiliBrowserBriefing secured = gateway.secureResult(LocalDate.of(2026, 7, 22), parsed);

        assertThat(secured.boundary()).contains("只读 MCP").doesNotContain("attacker");
        assertThat(secured.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.href()).isEqualTo("/v1/daily/bilibili/report?date=2026-07-22");
            assertThat(artifact.localPath()).isEqualTo(report.toString());
        });
    }
}
