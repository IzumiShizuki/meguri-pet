package com.meguri.core.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Revalidates a local Everything result immediately before its metadata is
 * shown or its user-approved content is read. This is deliberately stricter
 * than a path-prefix check: links, UNC paths and known credential/build areas
 * are never valid local resources.
 */
public final class LocalResourcePathPolicy {
    private static final Set<String> DENIED_SEGMENTS = Set.of(
            ".git", ".hg", ".svn", ".ssh", ".gnupg", ".aws", ".azure", ".kube", ".codex",
            "appdata", "node_modules", "__pycache__", ".venv", "venv", ".gradle", ".m2",
            "target", "build", "dist", "out", "windows", "program files", "program files (x86)",
            "programdata", "system volume information", "$recycle.bin", "recycler"
    );
    private static final Set<String> DENIED_FILE_NAMES = Set.of(
            ".env", "auth.json", "credentials.json", "common-config.yaml",
            "id_rsa", "id_ed25519", "known_hosts", "qianji-local-sync.secret.bat"
    );
    private static final Set<String> DENIED_EXTENSIONS = Set.of(
            "pem", "key", "p12", "pfx", "kdbx", "jks"
    );

    private LocalResourcePathPolicy() { }

    /** Resolves a safe local regular file or, when requested, directory. */
    public static Optional<ResolvedPath> resolve(String rawPath, boolean allowDirectory) {
        if (rawPath == null || rawPath.isBlank()) return Optional.empty();
        try {
            Path indexedPath = Path.of(rawPath).toAbsolutePath().normalize();
            if (!isLocalPath(indexedPath) || !Files.exists(indexedPath)
                    || Files.isSymbolicLink(indexedPath)) {
                return Optional.empty();
            }
            Path realPath = indexedPath.toRealPath();
            if (!isLocalPath(realPath) || containsDeniedPart(realPath)) return Optional.empty();
            BasicFileAttributes attributes = Files.readAttributes(realPath, BasicFileAttributes.class);
            if (!attributes.isRegularFile() && !(allowDirectory && attributes.isDirectory())) {
                return Optional.empty();
            }
            String name = realPath.getFileName() == null ? realPath.toString() : realPath.getFileName().toString();
            if (deniedFileName(name)) return Optional.empty();
            return Optional.of(new ResolvedPath(realPath, attributes));
        } catch (IOException | java.nio.file.InvalidPathException ignored) {
            return Optional.empty();
        }
    }

    /** Stable opaque ID used by the renderer; never expose a path-derived secret. */
    public static String stableId(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(path.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "local_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isLocalPath(Path path) {
        Path root = path.getRoot();
        if (root == null) return false;
        String value = root.toString();
        return !value.startsWith("\\\\") && !value.startsWith("//");
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

    /** Canonical safe path plus no-follow attributes from the same validation. */
    public record ResolvedPath(Path path, BasicFileAttributes attributes) { }
}
