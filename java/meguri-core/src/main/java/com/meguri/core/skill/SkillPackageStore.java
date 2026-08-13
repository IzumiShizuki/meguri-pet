package com.meguri.core.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Immutable SHA-256 package store outside the repository. */
public final class SkillPackageStore {
    private final Path packageRoot;

    public SkillPackageStore(Path dataRoot) {
        this.packageRoot = dataRoot.toAbsolutePath().normalize().resolve("skills").resolve("packages");
    }

    public StoredPackage ingest(Path stagingRoot, String expectedDigest) {
        Path root = stagingRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageStoreException("SKILL_STAGING_MISSING", "staging directory is unavailable");
        }
        List<Path> files = regularFiles(root);
        String digest = digest(root, files);
        if (expectedDigest != null && !expectedDigest.isBlank()
                && !digest.equalsIgnoreCase(expectedDigest.trim())) {
            throw new PackageStoreException("SKILL_DIGEST_MISMATCH", "package digest does not match expected value");
        }
        Path destination = packageRoot.resolve(digest).normalize();
        if (!destination.startsWith(packageRoot)) {
            throw new PackageStoreException("SKILL_PACKAGE_PATH_INVALID", "package destination is invalid");
        }
        try {
            Files.createDirectories(packageRoot);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("digest destination is not a directory");
                }
                verifyDigest(destination, digest);
                return new StoredPackage(digest, destination);
            }
            Path temporary = Files.createTempDirectory(packageRoot, ".ingest-");
            try {
                for (Path file : files) {
                    Path relative = root.relativize(file);
                    Path target = temporary.resolve(relative).normalize();
                    if (!target.startsWith(temporary)) throw new IOException("target escaped package root");
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
                moveIntoPlace(temporary, destination);
            } finally {
                cleanupTemporary(temporary);
            }
            verifyDigest(destination, digest);
            return new StoredPackage(digest, destination);
        } catch (IOException error) {
            throw new PackageStoreException("SKILL_PACKAGE_STORE_FAILED", "package could not be stored", error);
        }
    }

    /** Computes the canonical package identity without copying or executing content. */
    public String fingerprint(Path stagingRoot) {
        Path root = stagingRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageStoreException("SKILL_STAGING_MISSING", "staging directory is unavailable");
        }
        try {
            return digest(root, regularFiles(root));
        } catch (PackageStoreException unsafePackage) {
            return unsafeFingerprint(root, unsafePackage.code());
        }
    }

    private static String unsafeFingerprint(Path root, String failureCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "meguri.external-skill-quarantine.v1");
            update(digest, failureCode == null ? "SKILL_PACKAGE_UNSAFE" : failureCode);
            try (var entries = Files.walk(root, 32)) {
                for (Path value : entries.sorted(Comparator.comparing(entry -> {
                            try { return root.relativize(entry).toString().replace('\\', '/'); }
                            catch (IllegalArgumentException outside) { return "[outside-root]"; }
                        })).toList()) {
                    String relative;
                    try { relative = root.relativize(value).toString().replace('\\', '/'); }
                    catch (IllegalArgumentException outside) { relative = "[outside-root]"; }
                    update(digest, relative);
                    try {
                        BasicFileAttributes attributes = Files.readAttributes(
                                value, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        update(digest, attributes.isSymbolicLink() ? "link"
                                : attributes.isDirectory() ? "directory"
                                : attributes.isRegularFile() ? "file" : "other");
                        update(digest, Long.toString(attributes.size()));
                    } catch (IOException unreadable) {
                        update(digest, "unreadable");
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new PackageStoreException("SKILL_DIGEST_FAILED", "unsafe package fingerprint failed", error);
        }
    }

    private static void moveIntoPlace(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination);
        } catch (FileAlreadyExistsException raced) {
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) throw raced;
        }
    }

    private void cleanupTemporary(Path temporary) throws IOException {
        Path value = temporary.toAbsolutePath().normalize();
        if (!value.startsWith(packageRoot)
                || value.getFileName() == null
                || !value.getFileName().toString().startsWith(".ingest-")) {
            throw new IOException("refused to clean an unexpected package path");
        }
        if (!Files.exists(value, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(value)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    public Path resolve(String digest, String normalizedRelativePath) {
        if (digest == null || !digest.matches("[0-9a-fA-F]{64}")) {
            throw new PackageStoreException("SKILL_DIGEST_INVALID", "invalid package digest");
        }
        Path root = packageRoot.resolve(digest.toLowerCase()).normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageStoreException("SKILL_PACKAGE_MISSING", "Skill package is unavailable");
        }
        verifyDigest(root, digest.toLowerCase());
        String portable = normalizedRelativePath == null ? "" : normalizedRelativePath.replace('\\', '/');
        if (portable.indexOf(':') >= 0 || java.util.Arrays.stream(portable.split("/", -1))
                .anyMatch(".."::equals)) {
            throw new PackageStoreException("SKILL_PATH_INVALID", "path traversal is not allowed");
        }
        Path relative = Path.of(portable);
        if (relative.isAbsolute()) throw new PackageStoreException("SKILL_PATH_INVALID", "absolute path is not allowed");
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root)) throw new PackageStoreException("SKILL_PATH_INVALID", "path traversal is not allowed");
        return value;
    }

    private static void verifyDigest(Path root, String expectedDigest) {
        List<Path> files = regularFiles(root);
        if (!digest(root, files).equalsIgnoreCase(expectedDigest)) {
            throw new PackageStoreException(
                    "SKILL_DIGEST_MISMATCH", "stored package digest does not match its revision");
        }
    }

    private static List<Path> regularFiles(Path root) {
        ArrayList<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, java.util.Set.of(), 32, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    rejectLink(dir, attrs); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    rejectLink(file, attrs);
                    if (!attrs.isRegularFile()) throw new IOException("non-regular package entry");
                    files.add(file); return FileVisitResult.CONTINUE;
                }
                private void rejectLink(Path value, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(value)) throw new IOException("symbolic link rejected");
                }
            });
        } catch (IOException error) {
            throw new PackageStoreException("SKILL_PACKAGE_WALK_FAILED", "package contains an unsafe entry", error);
        }
        files.sort(Comparator.comparing(v -> root.relativize(v).toString().replace('\\', '/')));
        return List.copyOf(files);
    }

    private static String digest(Path root, List<Path> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "meguri.external-skill-package.v1");
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                update(digest, root.relativize(file).toString().replace('\\', '/'));
                update(digest, Long.toString(Files.size(file)));
                try (InputStream input = Files.newInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new PackageStoreException("SKILL_DIGEST_FAILED", "package digest failed", error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public record StoredPackage(String digest, Path path) { }
    public static final class PackageStoreException extends RuntimeException {
        private final String code;
        PackageStoreException(String code, String message) { super(message); this.code = code; }
        PackageStoreException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String code() { return code; }
    }
}
