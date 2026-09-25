package com.meguri.core.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.ResolvedExpression;
import com.meguri.core.dto.RuntimeState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves a semantic LLM cue to a deterministic sprite asset. */
public final class ExpressionResolver {
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() { };

    private final List<Map<String, Object>> rows;
    private final String buildId;
    private final RuntimeSpriteMap runtimeSpriteMap;

    public ExpressionResolver() {
        this(resolveDataRoot(), loadBuildId());
    }

    public ExpressionResolver(Path dataRoot) {
        this(dataRoot, loadBuildId());
    }

    public ExpressionResolver(Path dataRoot, String buildId) {
        this(dataRoot, buildId, findRuntimeMap(dataRoot));
    }

    ExpressionResolver(Path dataRoot, String buildId, Path runtimeMapPath) {
        this.buildId = Objects.requireNonNullElseGet(buildId, () -> "meguri_local_mock");
        this.runtimeSpriteMap = loadRuntimeMap(runtimeMapPath, this.buildId);
        this.rows = loadRows(dataRoot, this.buildId);
    }

    public String buildId() {
        return buildId;
    }

    public List<Map<String, Object>> rows() {
        return rows;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    /** Compatibility alias for callers ported from the Python ``self.map`` field. */
    public List<Map<String, Object>> getMap() {
        return rows;
    }

    public ResolvedExpression resolve(LlmResponse response, RuntimeState state) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(state, "state");
        ExpressionTag requestedTag = response.getExpressionTag() == null
                ? ExpressionTag.NEUTRAL : response.getExpressionTag();
        List<ExpressionTag> allowed = state.getAllowedExpressionTags();
        ExpressionTag tag = allowed == null || allowed.contains(requestedTag)
                ? requestedTag : ExpressionTag.NEUTRAL;
        Intensity intensity = response.getExpressionIntensity() == null
                ? Intensity.LOW : response.getExpressionIntensity();
        String outfit = state.getOutfitCode();
        String expressionCode = null;
        String spriteFile = null;

        // The reviewed runtime map is deliberately separate from the canonical
        // dataset export. The export remains provenance data; this map selects a
        // visually reviewed representative expression code shared by all eight
        // Meguri outfits for the temporary PNG renderer.
        String runtimeCode = runtimeSpriteMap.code(tag, intensity);
        if (runtimeCode != null) {
            expressionCode = runtimeCode;
            spriteFile = "ce" + outfit + runtimeCode + runtimeSpriteMap.size() + ".png";
            return new ResolvedExpression(tag, intensity, outfit, expressionCode, spriteFile);
        }

        // The canonical export is a list of rows. Keep matching deterministic and
        // prefer an exact intensity before the first same-tag fallback.
        for (ExpressionTag candidateTag : List.of(tag, ExpressionTag.NEUTRAL)) {
            if (candidateTag == null) continue;
            List<Map<String, Object>> matches = rows.stream()
                    .filter(row -> equals(row.get("outfit_code"), outfit))
                    .filter(row -> equals(row.get("expression_tag"), candidateTag.value()))
                    .filter(row -> !booleanValue(row.get("excluded_default")))
                    .filter(row -> equalsIgnoreCase(row.get("size"), "l"))
                    .toList();
            Map<String, Object> chosen = matches.stream()
                    .filter(row -> equals(row.get("expression_intensity"), intensity.value()))
                    .findFirst()
                    .orElse(matches.stream().findFirst().orElse(null));
            if (chosen == null) continue;
            String rowBuildId = text(chosen.get("build_id"));
            if (rowBuildId != null && !rowBuildId.equals(buildId)) {
                throw new IllegalStateException("expression map build_id mismatch: expected "
                        + buildId + ", got " + rowBuildId);
            }
            expressionCode = text(chosen.get("expression_code"));
            String projectPath = text(chosen.get("project_path"));
            spriteFile = projectPath == null || projectPath.isBlank()
                    ? null : Path.of(projectPath).getFileName().toString();
            tag = candidateTag;
            break;
        }

        // Preserve a useful deterministic fallback even when an export is absent.
        if (spriteFile == null && expressionCode != null) {
            spriteFile = "ce" + outfit + expressionCode + "l.png";
        }
        return new ResolvedExpression(tag, intensity, outfit, expressionCode, spriteFile);
    }

    public ResolvedExpression resolveExpression(LlmResponse response, RuntimeState state) {
        return resolve(response, state);
    }

    private static Path findRuntimeMap(Path dataRoot) {
        String configured = System.getenv("MEGURI_SPRITE_RUNTIME_MAP");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("meguri.sprite-runtime-map");
        }
        if (configured != null && !configured.isBlank()) return Path.of(configured.trim());
        if (dataRoot == null) return null;
        Path cursor = dataRoot.toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("configs").resolve("meguri_sprite_runtime_map.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static RuntimeSpriteMap loadRuntimeMap(Path path, String buildId) {
        if (path == null) return RuntimeSpriteMap.empty();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("configured sprite runtime map does not exist: " + path);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(Files.readString(path), new TypeReference<>() { });
            String rowBuildId = text(root.get("build_id"));
            if (!Objects.equals(buildId, rowBuildId)) {
                throw new IllegalStateException("sprite runtime map build_id mismatch: expected "
                        + buildId + ", got " + rowBuildId);
            }
            String size = Objects.requireNonNullElse(text(root.get("size")), "l").toLowerCase(Locale.ROOT);
            if (!size.equals("l") && !size.equals("m")) {
                throw new IllegalStateException("sprite runtime map size must be l or m");
            }
            Object expressionsValue = root.get("expressions");
            if (!(expressionsValue instanceof Map<?, ?> expressions)) {
                throw new IllegalStateException("sprite runtime map expressions must be an object");
            }
            Map<ExpressionTag, Map<Intensity, String>> codes = new LinkedHashMap<>();
            for (ExpressionTag tag : ExpressionTag.values()) {
                Object intensityValue = expressions.get(tag.value());
                if (!(intensityValue instanceof Map<?, ?> intensities)) {
                    throw new IllegalStateException("sprite runtime map is missing expression: " + tag.value());
                }
                Map<Intensity, String> byIntensity = new LinkedHashMap<>();
                for (Intensity intensity : Intensity.values()) {
                    String code = text(intensities.get(intensity.value()));
                    if (code == null || !code.matches("\\d{3}")) {
                        throw new IllegalStateException("invalid sprite code for " + tag.value()
                                + "/" + intensity.value() + ": " + code);
                    }
                    byIntensity.put(intensity, code);
                }
                codes.put(tag, Map.copyOf(byIntensity));
            }
            return new RuntimeSpriteMap(size, Map.copyOf(codes));
        } catch (IOException error) {
            throw new IllegalStateException("failed to read sprite runtime map: " + path, error);
        }
    }

    private static List<Map<String, Object>> loadRows(Path dataRoot, String buildId) {
        if (dataRoot == null) return List.of();
        Path json = dataRoot.resolve("exports").resolve("expression_map").resolve("expression_map.json");
        if (Files.isRegularFile(json)) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<Map<String, Object>> parsed = mapper.readValue(Files.readString(json), ROWS);
                return immutableRows(parsed);
            } catch (IOException ignored) {
                // A malformed optional export should not take down the local mock
                // runtime; resolution falls back to neutral below.
                return List.of();
            }
        }
        return List.of();
    }

    private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : source) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Path resolveDataRoot() {
        String configured = System.getenv("MEGURI_DATA_ROOT");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("meguri.data-root");
        }
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("datasets").resolve("meguri");
            if (Files.exists(candidate)) return candidate;
        }
        return cwd.resolve("datasets").resolve("meguri");
    }

    private static String loadBuildId() {
        String configured = System.getenv("MEGURI_BUILD_ID");
        if (configured == null || configured.isBlank()) configured = System.getProperty("meguri.build-id");
        if (configured != null && !configured.isBlank()) return configured.trim();
        Path report = resolveDataRoot().resolve("build_report.json");
        try {
            Map<String, Object> value = new ObjectMapper().readValue(Files.readString(report), new TypeReference<>() { });
            String id = text(value.get("build_id"));
            if (id != null && !id.isBlank()) return id;
        } catch (IOException | RuntimeException ignored) {
            // fall through to the explicit local fallback
        }
        return "meguri_local_mock";
    }

    private static boolean equals(Object left, Object right) {
        return left != null && right != null && String.valueOf(left).equals(String.valueOf(right));
    }

    private static boolean equalsIgnoreCase(Object left, String right) {
        return left != null && right != null && right.equalsIgnoreCase(String.valueOf(left));
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record RuntimeSpriteMap(String size, Map<ExpressionTag, Map<Intensity, String>> codes) {
        static RuntimeSpriteMap empty() {
            return new RuntimeSpriteMap("l", Map.of());
        }

        String code(ExpressionTag tag, Intensity intensity) {
            Map<Intensity, String> values = codes.get(tag);
            return values == null ? null : values.get(intensity);
        }
    }
}
