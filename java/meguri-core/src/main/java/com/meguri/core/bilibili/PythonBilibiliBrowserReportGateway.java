package com.meguri.core.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs the standard-library Python History reader outside the WebFlux event loop.
 * The process receives History paths only; no cookie or Bilibili credential path is accepted.
 */
@Service
public final class PythonBilibiliBrowserReportGateway implements BilibiliBrowserReportGateway {
    private static final Set<String> ALLOWED_STATUSES = Set.of("ready", "empty", "unavailable");
    private static final Set<String> ALLOWED_DATA_SOURCES = Set.of(
            "account_mcp", "browser_history", "browser_history_fallback", "unavailable");
    private static final Set<String> ALLOWED_SYNC_STATUSES = Set.of(
            "success", "risk_stop", "error", "stale", "not_checked", "unavailable");

    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String pythonExecutable;
    private final Path scriptFile;
    private final Path projectRoot;
    private final ZoneId timezone;
    private final long timeoutSeconds;
    private final String mcpUrl;
    private final Path mcpTokenFile;
    private final long mcpTimeoutSeconds;
    private final Path syncStatusFile;
    private final String chromeHistory;
    private final String edgeHistory;

    @Autowired
    public PythonBilibiliBrowserReportGateway(
            ObjectMapper mapper,
            @Value("${meguri.bilibili-report.enabled:${MEGURI_BILIBILI_REPORT_ENABLED:true}}") boolean enabled,
            @Value("${meguri.bilibili-report.python:${MEGURI_BILIBILI_REPORT_PYTHON:D:/environment/anaconda3/envs/py314/python.exe}}")
            String pythonExecutable,
            @Value("${meguri.bilibili-report.script:${MEGURI_BILIBILI_REPORT_SCRIPT:D:/program/meguri-pet/tools/generate_bilibili_browser_report.py}}")
            String scriptFile,
            @Value("${meguri.bilibili-report.project-root:${MEGURI_BILIBILI_REPORT_PROJECT_ROOT:D:/program/meguri-pet}}")
            String projectRoot,
            @Value("${meguri.bilibili-report.timezone:${MEGURI_BILIBILI_REPORT_TIMEZONE:Asia/Shanghai}}")
            String timezone,
            @Value("${meguri.bilibili-report.timeout-seconds:${MEGURI_BILIBILI_REPORT_TIMEOUT_SECONDS:20}}")
            long timeoutSeconds,
            @Value("${meguri.bilibili-report.mcp-url:${MEGURI_BHF_MCP_URL:http://127.0.0.1:8899/mcp/}}")
            String mcpUrl,
            @Value("${meguri.bilibili-report.mcp-token-file:}")
            String mcpTokenFile,
            @Value("${meguri.bilibili-report.mcp-timeout-seconds:${MEGURI_BHF_MCP_TIMEOUT_SECONDS:5}}")
            long mcpTimeoutSeconds,
            @Value("${meguri.bilibili-report.sync-status-file:${MEGURI_BHF_SYNC_STATUS_FILE:}}")
            String syncStatusFile,
            @Value("${meguri.bilibili-report.chrome-history:${MEGURI_CHROME_HISTORY:}}") String chromeHistory,
            @Value("${meguri.bilibili-report.edge-history:${MEGURI_EDGE_HISTORY:}}") String edgeHistory) {
        this(mapper, enabled, pythonExecutable, Path.of(scriptFile), Path.of(projectRoot),
                ZoneId.of(timezone), timeoutSeconds, mcpUrl, mcpTokenFile, mcpTimeoutSeconds, syncStatusFile,
                chromeHistory, edgeHistory);
    }

    PythonBilibiliBrowserReportGateway(
            ObjectMapper mapper,
            boolean enabled,
            String pythonExecutable,
            Path scriptFile,
            Path projectRoot,
            ZoneId timezone,
            long timeoutSeconds,
            String chromeHistory,
            String edgeHistory) {
        this(mapper, enabled, pythonExecutable, scriptFile, projectRoot, timezone, timeoutSeconds,
                "", "", 5, "", chromeHistory, edgeHistory);
    }

    PythonBilibiliBrowserReportGateway(
            ObjectMapper mapper,
            boolean enabled,
            String pythonExecutable,
            Path scriptFile,
            Path projectRoot,
            ZoneId timezone,
            long timeoutSeconds,
            String mcpUrl,
            String mcpTokenFile,
            long mcpTimeoutSeconds,
            String syncStatusFile,
            String chromeHistory,
            String edgeHistory) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.pythonExecutable = pythonExecutable;
        this.scriptFile = scriptFile.toAbsolutePath().normalize();
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.timezone = timezone;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.mcpUrl = mcpUrl == null ? "" : mcpUrl.trim();
        this.mcpTokenFile = mcpTokenFile == null || mcpTokenFile.isBlank()
                ? null : Path.of(mcpTokenFile).toAbsolutePath().normalize();
        this.mcpTimeoutSeconds = Math.max(1, mcpTimeoutSeconds);
        this.syncStatusFile = syncStatusFile == null || syncStatusFile.isBlank()
                ? null : Path.of(syncStatusFile).toAbsolutePath().normalize();
        this.chromeHistory = chromeHistory == null ? "" : chromeHistory.trim();
        this.edgeHistory = edgeHistory == null ? "" : edgeHistory.trim();
    }

    @Override
    public Mono<BilibiliBrowserBriefing> generate(LocalDate targetDate) {
        LocalDate effectiveDate = targetDate == null ? LocalDate.now(timezone).minusDays(1) : targetDate;
        if (!enabled) {
            return Mono.just(BilibiliBrowserBriefing.unavailable(effectiveDate.toString(), "Bilibili 浏览器访问日报未启用。"));
        }
        return Mono.fromCallable(() -> generateBlocking(effectiveDate))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(BilibiliBrowserBriefing.unavailable(
                        effectiveDate.toString(), "Bilibili 浏览器访问日报生成失败。"));
    }

    BilibiliBrowserBriefing generateBlocking(LocalDate targetDate) throws IOException, InterruptedException {
        if (!Files.isRegularFile(scriptFile)) {
            return BilibiliBrowserBriefing.unavailable(targetDate.toString(), "未找到浏览器 History 日报脚本。 ");
        }

        List<String> command = new ArrayList<>(List.of(
                pythonExecutable,
                scriptFile.toString(),
                "--project-root", projectRoot.toString(),
                "--date", targetDate.toString(),
                "--timezone", timezone.getId()));
        if (!mcpUrl.isBlank()) {
            command.add("--mcp-url");
            command.add(mcpUrl);
        }
        if (mcpTokenFile != null) {
            command.add("--mcp-token-file");
            command.add(mcpTokenFile.toString());
        }
        command.add("--mcp-timeout-seconds");
        command.add(Long.toString(mcpTimeoutSeconds));
        if (syncStatusFile != null) {
            command.add("--sync-status-file");
            command.add(syncStatusFile.toString());
        }
        addConfiguredHistory(command, "Chrome/Configured", chromeHistory);
        addConfiguredHistory(command, "Edge/Configured", edgeHistory);

        Path stdoutFile = Files.createTempFile("meguri-bilibili-report-", ".json");
        Path stderrFile = Files.createTempFile("meguri-bilibili-report-", ".stderr");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return BilibiliBrowserBriefing.unavailable(targetDate.toString(), "浏览器 History 日报生成超时。 ");
            }
            String output = Files.readString(stdoutFile, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 && process.exitValue() != 2) {
                return BilibiliBrowserBriefing.unavailable(
                        targetDate.toString(), "Bilibili 日报脚本执行失败（退出码 " + process.exitValue() + "）。");
            }
            if (output.isEmpty()) {
                return BilibiliBrowserBriefing.unavailable(targetDate.toString(), "Bilibili 日报没有返回结果。 ");
            }
            BilibiliBrowserBriefing parsed = mapper.readValue(output, BilibiliBrowserBriefing.class);
            if (process.exitValue() == 2 && !"unavailable".equals(parsed.status())) {
                return BilibiliBrowserBriefing.unavailable(targetDate.toString(), "Bilibili 日报退出状态无效。 ");
            }
            return secureResult(targetDate, parsed);
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    BilibiliBrowserBriefing secureResult(LocalDate targetDate, BilibiliBrowserBriefing parsed) {
        if (!targetDate.toString().equals(parsed.date())
                || !ALLOWED_STATUSES.contains(parsed.status())
                || !ALLOWED_DATA_SOURCES.contains(parsed.dataSource())
                || !ALLOWED_SYNC_STATUSES.contains(parsed.syncStatus())) {
            return BilibiliBrowserBriefing.unavailable(targetDate.toString(), "Bilibili 日报返回了无效结果。 ");
        }
        Path expectedReport = reportPath(targetDate);
        List<BilibiliBrowserArtifact> artifacts = Files.isRegularFile(expectedReport)
                ? List.of(new BilibiliBrowserArtifact(
                        "account_mcp".equals(parsed.dataSource())
                                ? "Bilibili 账号观看日报（Markdown）"
                                : "Bilibili 浏览器降级日报（Markdown）",
                        "/v1/daily/bilibili/report?date=" + targetDate,
                        expectedReport.toString()))
                : List.of();
        return new BilibiliBrowserBriefing(
                parsed.status(), parsed.date(), parsed.generatedAt(), parsed.dataSource(), parsed.syncStatus(),
                parsed.uniqueVideos(), parsed.totalVisits(),
                parsed.firstVisitedAt(), parsed.lastVisitedAt(), parsed.summary(), parsed.videos(), parsed.sources(),
                privacyBoundary(parsed.dataSource()), artifacts, parsed.error());
    }

    private static String privacyBoundary(String dataSource) {
        if ("account_mcp".equals(dataSource)) {
            return BilibiliBrowserBriefing.ACCOUNT_MCP_PRIVACY_BOUNDARY;
        }
        String boundary = BilibiliBrowserBriefing.BROWSER_PRIVACY_BOUNDARY;
        if ("browser_history_fallback".equals(dataSource)) {
            return boundary + " 账号只读 MCP 不可用，本次已明确降级为浏览器页面访问记录。";
        }
        return boundary;
    }

    private Path reportPath(LocalDate targetDate) {
        return projectRoot.resolve("reports").resolve("daily")
                .resolve("bilibili-" + targetDate + ".md").normalize();
    }

    private static void addConfiguredHistory(List<String> command, String name, String historyFile) {
        if (historyFile == null || historyFile.isBlank()) return;
        command.add("--history");
        command.add(name + "=" + historyFile);
    }
}
