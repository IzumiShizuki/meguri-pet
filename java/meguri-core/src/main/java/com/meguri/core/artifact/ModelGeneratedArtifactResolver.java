package com.meguri.core.artifact;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts explicit model-returned output references into safe desktop
 * artifacts. It never accepts arbitrary local paths or executable file types.
 */
public final class ModelGeneratedArtifactResolver {
    private static final Pattern MARKER = Pattern.compile(
            "\\[\\[meguri-artifact:(reports|output)/([^\\]\\r\\n]+)]]",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> PASSIVE_EXTENSIONS = Set.of(
            ".md", ".pdf", ".jpg", ".jpeg", ".png", ".gif", ".webp",
            ".txt", ".csv", ".json", ".html");
    private static final int MAX_ARTIFACTS = 3;
    private final Path projectRoot;

    public ModelGeneratedArtifactResolver() {
        this(Path.of(System.getenv().getOrDefault("MEGURI_PROJECT_ROOT", "D:\\program\\meguri-pet")));
    }

    public ModelGeneratedArtifactResolver(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /** Extracts up to three existing generated files from a user-visible model reply. */
    public List<Artifact> resolve(String reply) {
        if (reply == null || reply.isBlank()) return List.of();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(reply);
        while (matcher.find() && candidates.size() < MAX_ARTIFACTS) {
            candidates.add(matcher.group(1).toLowerCase(Locale.ROOT) + "/" + matcher.group(2));
        }
        // A generated absolute path may be returned by a tool result without the
        // marker. Accept it only if resolveCandidate proves it remains below a
        // generated root; bare arbitrary paths are therefore still inert.
        for (String token : reply.split("[\\r\\n\\t ]+")) {
            if (candidates.size() >= MAX_ARTIFACTS) break;
            String normalized = token.replaceAll("^[\\[\\(\"']+|[\\]\\)\"',.;:]+$", "");
            if (normalized.matches("(?i)^[a-z]:[\\\\/].*")) candidates.add(normalized);
        }
        List<Artifact> artifacts = new ArrayList<>();
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (String candidate : candidates) {
            Artifact artifact = resolveCandidate(candidate);
            if (artifact != null && seen.add(Path.of(artifact.localPath()))) artifacts.add(artifact);
            if (artifacts.size() >= MAX_ARTIFACTS) break;
        }
        return List.copyOf(artifacts);
    }

    private Artifact resolveCandidate(String value) {
        try {
            Path candidate = Path.of(value);
            if (!candidate.isAbsolute()) candidate = projectRoot.resolve(candidate);
            Path realPath = candidate.normalize().toRealPath();
            if (!Files.isRegularFile(realPath) || !passiveExtension(realPath)) return null;
            for (String scope : List.of("reports", "output")) {
                try {
                    Path root = projectRoot.resolve(scope).toRealPath();
                    if (!realPath.startsWith(root)) continue;
                    Path relative = root.relativize(realPath);
                    if (relative.getNameCount() == 0) return null;
                    return new Artifact(realPath.getFileName().toString(),
                            href(scope, relative), realPath.toString());
                } catch (Exception ignored) {
                    // One generated root may not exist yet; the other is still valid.
                }
            }
        } catch (Exception ignored) {
            // Nonexistent files, unsafe paths and unavailable roots are not artifacts.
        }
        return null;
    }

    private static boolean passiveExtension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return PASSIVE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String href(String scope, Path relative) {
        List<String> segments = new ArrayList<>();
        segments.add(scope);
        relative.forEach(segment -> segments.add(URLEncoder.encode(
                segment.toString(), StandardCharsets.UTF_8).replace("+", "%20")));
        return "/" + String.join("/", segments);
    }

    /** Renderer-safe artifact properties emitted in a turn event. */
    public record Artifact(String label, String href, String localPath) { }
}
