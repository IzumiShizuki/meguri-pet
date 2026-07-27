package com.meguri.core.resource;

import com.meguri.core.resources.LocalResourceCandidate;
import com.meguri.core.resources.ResourceSearchGateway;
import com.meguri.core.resources.ResourceSearchResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything-backed resource picker with a second, authoritative path gate.
 * It only returns metadata and never reads, opens or executes a result.
 */
public final class EverythingResourceSearchGateway implements ResourceSearchGateway {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int HARD_RESULT_LIMIT = 20;
    private static final int SEARCH_MULTIPLIER = 10;

    private static final Set<String> DENIED_SEGMENTS = Set.of(
            ".git", ".hg", ".svn", ".ssh", ".gnupg", ".aws", ".azure", ".kube", ".codex",
            "appdata", "node_modules", "__pycache__", ".venv", "venv", ".gradle", ".m2",
            "target", "build", "dist", "out"
    );
    private static final Set<String> DENIED_FILE_NAMES = Set.of(
            ".env", "auth.json", "credentials.json", "common-config.yaml",
            "id_rsa", "id_ed25519", "known_hosts", "qianji-local-sync.secret.bat"
    );
    private static final Set<String> DENIED_EXTENSIONS = Set.of(
            "pem", "key", "p12", "pfx", "kdbx", "jks"
    );

    private final EsEverythingSearchGateway client;
    private final List<Path> allowedRoots;

    public EverythingResourceSearchGateway(EsEverythingSearchGateway client, List<Path> allowedRoots) {
        this.client = client;
        this.allowedRoots = allowedRoots.stream()
                .map(EverythingResourceSearchGateway::normalizedRealPath)
                .distinct()
                .toList();
    }

    @Override
    public Mono<ResourceSearchResponse> search(String rawQuery, int requestedLimit) {
        return Mono.fromCallable(() -> searchBlocking(rawQuery, requestedLimit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    ResourceSearchResponse searchBlocking(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.startsWith("@")) query = query.substring(1).strip();
        if (query.isBlank()) return ResourceSearchResponse.unavailable("", "Resource query must not be blank.");
        if (query.length() > MAX_QUERY_LENGTH) {
            return ResourceSearchResponse.unavailable(query.substring(0, MAX_QUERY_LENGTH),
                    "Resource query must not exceed 200 characters.");
        }
        int limit = Math.clamp(requestedLimit, 1, HARD_RESULT_LIMIT);
        if (!client.available()) {
            return ResourceSearchResponse.unavailable(query,
                    "Everything is running, but its official ES command-line client is not installed.");
        }

        List<EverythingSearchHit> hits = client.search(query, Math.min(500, limit * SEARCH_MULTIPLIER));
        List<LocalResourceCandidate> candidates = new ArrayList<>(limit);
        for (EverythingSearchHit hit : hits) {
            safeCandidate(hit).ifPresent(candidate -> {
                if (candidates.size() < limit) candidates.add(candidate);
            });
            if (candidates.size() >= limit) break;
        }
        return new ResourceSearchResponse(query, true, candidates,
                candidates.isEmpty() ? "No safe local resource matched the query." : "");
    }

    private java.util.Optional<LocalResourceCandidate> safeCandidate(EverythingSearchHit hit) {
        if (hit == null || hit.path() == null || hit.path().isBlank()) return java.util.Optional.empty();
        try {
            Path indexedPath = Path.of(hit.path()).toAbsolutePath().normalize();
            if (!Files.exists(indexedPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(indexedPath)) {
                return java.util.Optional.empty();
            }
            Path realPath = indexedPath.toRealPath();
            Path realRoot = allowedRootFor(realPath);
            if (realRoot == null || containsDeniedPart(realRoot.relativize(realPath))) return java.util.Optional.empty();
            BasicFileAttributes attributes = Files.readAttributes(realPath, BasicFileAttributes.class);
            String name = realPath.getFileName() == null ? realPath.toString() : realPath.getFileName().toString();
            if (deniedFileName(name)) return java.util.Optional.empty();
            Long size = attributes.isDirectory() ? null : attributes.size();
            Instant modified = attributes.lastModifiedTime().toInstant();
            return java.util.Optional.of(new LocalResourceCandidate(
                    stableId(realPath), name, realPath.toString(), kind(name, attributes.isDirectory()),
                    size, modified.toString()));
        } catch (IOException | java.nio.file.InvalidPathException ignored) {
            return java.util.Optional.empty();
        }
    }

    private Path allowedRootFor(Path candidate) {
        return allowedRoots.stream().filter(candidate::startsWith).findFirst().orElse(null);
    }

    private static boolean containsDeniedPart(Path path) {
        for (Path part : path) {
            if (DENIED_SEGMENTS.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean deniedFileName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (DENIED_FILE_NAMES.contains(lower) || lower.startsWith(".env.")) return true;
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && DENIED_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    private static String kind(String name, boolean directory) {
        if (directory) return "directory";
        return "file";
    }

    private static String stableId(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(path.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "local_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Path normalizedRealPath(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }
}
