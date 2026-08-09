package com.meguri.core.daily;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** File-backed, restart-safe inbox for reports produced by an authorized local machine. */
@Service
public final class DailyReportStore {
    private static final Pattern KIND = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final int MAX_RENDER_PAYLOAD_BYTES = 32 * 1024;

    private final ObjectMapper mapper;
    private final Path root;

    @Autowired
    public DailyReportStore(
            ObjectMapper mapper,
            @Value("${meguri.daily-reports.directory:${MEGURI_DAILY_REPORT_DIR:${user.home}/.meguri/daily-reports}}")
            String directory) {
        this(mapper, Path.of(directory));
    }

    DailyReportStore(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized DailyReportReceipt save(DailyReportUpload upload) throws IOException {
        String expectedId = upload.kind() + ":" + upload.date();
        if (!expectedId.equals(upload.reportId())) {
            throw new IllegalArgumentException("report_id must match kind and date");
        }
        String actualHash = sha256(upload.markdown());
        if (!MessageDigest.isEqual(
                actualHash.getBytes(StandardCharsets.US_ASCII),
                upload.markdownSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("markdown_sha256 does not match markdown");
        }
        if (upload.renderPayload() != null) {
            if (!upload.renderPayload().isObject()) {
                throw new IllegalArgumentException("render_payload must be an object");
            }
            if (mapper.writeValueAsBytes(upload.renderPayload()).length > MAX_RENDER_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("render_payload is too large");
            }
        }

        Files.createDirectories(root);
        DailyReportReceipt receipt = new DailyReportReceipt(
                upload.schemaVersion(), upload.reportId(), upload.kind(), upload.date(),
                upload.title(), upload.summary(), upload.deliveryText(), upload.deliverySpeechText(),
                upload.generatedAt(), upload.publishedAt(), OffsetDateTime.now(ZoneOffset.UTC),
                upload.dataSource(), upload.syncStatus(), upload.uniqueVideos(), upload.totalVisits(),
                upload.renderPayload(),
                upload.markdownSha256(), markdownHref(upload.kind(), upload.date().toString()));
        String stem = upload.kind() + "-" + upload.date();
        atomicWrite(root.resolve(stem + ".md"), upload.markdown().getBytes(StandardCharsets.UTF_8));
        byte[] metadata = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt);
        atomicWrite(root.resolve(stem + ".json"), metadata);
        atomicWrite(root.resolve("latest-" + upload.kind() + ".json"), metadata);
        return receipt;
    }

    public Optional<DailyReportReceipt> latest(String kind) throws IOException {
        requireKind(kind);
        Path path = root.resolve("latest-" + kind + ".json").normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(mapper.readValue(path.toFile(), DailyReportReceipt.class));
    }

    public Optional<Path> markdown(String kind, String date) {
        requireKind(kind);
        if (!date.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) {
            throw new IllegalArgumentException("date is invalid");
        }
        Path path = root.resolve(kind + "-" + date + ".md").normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(path);
    }

    private static void requireKind(String kind) {
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("report kind is invalid");
        }
    }

    private static String markdownHref(String kind, String date) {
        return "/v1/daily/reports/" + kind + "/" + date + "/markdown";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
