package com.meguri.core.bilibili;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Loopback endpoint for on-demand local browser page-visit reports. */
@RestController
@RequestMapping("/v1/daily/bilibili")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class BilibiliBrowserReportController {
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final BilibiliBrowserReportGateway gateway;
    private final Path projectRoot;
    private final String publicOrigin;

    @Autowired
    public BilibiliBrowserReportController(
            BilibiliBrowserReportGateway gateway,
            @Value("${meguri.bilibili-report.project-root:${MEGURI_BILIBILI_REPORT_PROJECT_ROOT:D:/program/meguri-pet}}")
            String projectRoot,
            @Value("${meguri.public-origin:${MEGURI_CORE_PUBLIC_ORIGIN:http://127.0.0.1:18080}}")
            String publicOrigin) {
        this(gateway, Path.of(projectRoot), publicOrigin);
    }

    BilibiliBrowserReportController(BilibiliBrowserReportGateway gateway, Path projectRoot) {
        this(gateway, projectRoot, "http://127.0.0.1:18080");
    }

    BilibiliBrowserReportController(
            BilibiliBrowserReportGateway gateway, Path projectRoot, String publicOrigin) {
        this.gateway = gateway;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.publicOrigin = publicOrigin.replaceAll("/+$", "");
    }

    @GetMapping(path = "/briefing", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<BilibiliBrowserBriefing> briefing(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            ServerHttpRequest request) {
        return gateway.generate(date).map(result -> absoluteArtifacts(result, request));
    }

    @GetMapping(path = "/report", produces = "text/markdown;charset=UTF-8")
    public Mono<ResponseEntity<Resource>> report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Mono.fromCallable(() -> reportResponse(date)).subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Resource> reportResponse(LocalDate date) {
        Path report = projectRoot.resolve("reports").resolve("daily")
                .resolve("bilibili-" + date + ".md").normalize();
        if (!report.startsWith(projectRoot) || !Files.isRegularFile(report)) {
            return ResponseEntity.notFound().build();
        }
        String filename = report.getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(report));
    }

    private BilibiliBrowserBriefing absoluteArtifacts(
            BilibiliBrowserBriefing result, ServerHttpRequest request) {
        String origin = request.getURI().isAbsolute() && request.getURI().getRawAuthority() != null
                ? request.getURI().getScheme() + "://" + request.getURI().getRawAuthority()
                : publicOrigin;
        List<BilibiliBrowserArtifact> artifacts = result.artifacts().stream()
                .map(artifact -> new BilibiliBrowserArtifact(
                        artifact.label(), absoluteHref(origin, artifact.href()), artifact.localPath()))
                .toList();
        return new BilibiliBrowserBriefing(
                result.status(), result.date(), result.generatedAt(), result.dataSource(), result.syncStatus(),
                result.uniqueVideos(), result.totalVisits(),
                result.firstVisitedAt(), result.lastVisitedAt(), result.summary(), result.videos(), result.sources(),
                result.boundary(), artifacts, result.error());
    }

    private static String absoluteHref(String origin, String href) {
        if (href == null || href.isBlank() || !href.startsWith("/")) return href;
        return origin + href;
    }
}
