package com.meguri.core.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Uses voidtools' official ES command-line client without invoking a shell.
 * Everything.exe itself is deliberately not treated as a CLI: it opens a GUI
 * and cannot safely return structured search results on stdout.
 */
public class EsEverythingSearchGateway {
    private final boolean enabled;
    private final Path executable;
    private final Duration timeout;
    private final int queryResultCap;

    public EsEverythingSearchGateway(boolean enabled, Path executable, Duration timeout, int queryResultCap) {
        this.enabled = enabled;
        this.executable = executable.toAbsolutePath().normalize();
        this.timeout = timeout.compareTo(Duration.ofMillis(250)) < 0 ? Duration.ofMillis(250) : timeout;
        this.queryResultCap = Math.clamp(queryResultCap, 1, 500);
    }

    public boolean available() {
        return enabled
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                && Files.isRegularFile(executable)
                && Files.isExecutable(executable);
    }

    public List<EverythingSearchHit> search(String query, int maxResults) {
        if (!available()) return List.of();
        int requested = Math.clamp(maxResults, 1, queryResultCap);
        List<String> command = List.of(
                executable.toString(),
                "-n", Integer.toString(requested),
                "-timeout", Long.toString(timeout.toMillis()),
                "-sort", "date-modified-descending",
                "-full-path-and-name",
                "-size",
                "-dm",
                "-size-format", "1",
                "-no-digit-grouping",
                "-date-format", "3",
                "-csv",
                "-no-header",
                query
        );

        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            CompletableFuture<byte[]> stdoutFuture = readAsync(process.getInputStream());
            CompletableFuture<byte[]> stderrFuture = readAsync(process.getErrorStream());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new EverythingSearchException("Everything ES query timed out");
            }
            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new EverythingSearchException("Everything ES query failed with exit code "
                        + process.exitValue() + (stderr.isBlank() ? "" : ": " + safeError(stderr)));
            }
            return parseCsv(stdout);
        } catch (IOException exception) {
            throw new EverythingSearchException("Unable to start Everything ES", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EverythingSearchException("Everything ES query was interrupted", exception);
        } catch (CompletionException exception) {
            throw new EverythingSearchException("Unable to read Everything ES output", exception.getCause());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static CompletableFuture<byte[]> readAsync(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return stream.readAllBytes();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    static List<EverythingSearchHit> parseCsv(String csv) {
        List<List<String>> rows = parseRows(stripBom(csv));
        List<EverythingSearchHit> hits = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            if (row.isEmpty() || row.getFirst().isBlank()) continue;
            Long size = row.size() > 1 ? parseLong(row.get(1)) : null;
            Instant modified = row.size() > 2 ? parseInstant(row.get(2)) : null;
            hits.add(new EverythingSearchHit(row.getFirst(), size, modified));
        }
        return List.copyOf(hits);
    }

    private static List<List<String>> parseRows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < csv.length(); index++) {
            char value = csv.charAt(index);
            if (quoted) {
                if (value == '"' && index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else if (value == '"') {
                    quoted = false;
                } else {
                    field.append(value);
                }
            } else if (value == '"') {
                quoted = true;
            } else if (value == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (value == '\n' || value == '\r') {
                if (value == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') index++;
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(List.copyOf(row));
                row.clear();
            } else {
                field.append(value);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    private static String safeError(String stderr) {
        String oneLine = stderr.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.substring(0, Math.min(200, oneLine.length()));
    }

    public static final class EverythingSearchException extends RuntimeException {
        public EverythingSearchException(String message) {
            super(message);
        }

        public EverythingSearchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
