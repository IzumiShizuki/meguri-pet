package com.meguri.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small offline retriever for the canonical Meguri JSONL exports. It preserves
 * the Python provider's deterministic lexical fallback while leaving a clear
 * seam for a LangChain4j EmbeddingStoreContentRetriever in production.
 */
public class CanonicalRagRetriever implements RagProvider {
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private final ObjectMapper mapper;
    private final List<Row> rows;
    private final String expectedBuildId;

    public CanonicalRagRetriever(Path dataRoot) {
        this(dataRoot, new ObjectMapper(), resolveBuildId(dataRoot));
    }

    public CanonicalRagRetriever(Path dataRoot, ObjectMapper mapper, String expectedBuildId) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.expectedBuildId = expectedBuildId == null || expectedBuildId.isBlank() ? null : expectedBuildId;
        this.rows = loadRows(dataRoot);
    }

    protected CanonicalRagRetriever(ObjectMapper mapper, String expectedBuildId, List<Row> rows) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.expectedBuildId = expectedBuildId;
        this.rows = List.copyOf(rows == null ? List.of() : rows);
    }

    @Override
    public List<String> search(String query, RuntimeState state, int limit) {
        if (limit <= 0 || rows.isEmpty()) return List.of();
        Set<String> terms = terms(query);
        Relationship relationship = state == null ? null : state.getRelationshipProfile();
        List<Scored> scored = new ArrayList<>();
        for (Row row : rows) {
            String text = row.text();
            if (text.isBlank()) continue;
            int score = 0;
            String lower = text.toLowerCase(Locale.ROOT);
            for (String term : terms) if (!term.isBlank() && lower.contains(term)) score++;
            if (row.relationshipStage() == null || (relationship != null && row.relationshipStage().equals(relationship.value()))) score++;
            scored.add(new Scored(score, row.order(), text));
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order))
                .limit(limit)
                .map(Scored::text)
                .toList();
    }

    public int size() { return rows.size(); }

    private List<Row> loadRows(Path dataRoot) {
        if (dataRoot == null) return List.of();
        List<Path> candidates = List.of(
                dataRoot.resolve("exports").resolve("rag").resolve("chunks_train.jsonl"),
                dataRoot.resolve("knowledge").resolve("style_scenes.jsonl"));
        for (Path path : candidates) {
            if (!Files.isRegularFile(path)) continue;
            List<Row> loaded = readJsonLines(path);
            if (!loaded.isEmpty()) return loaded;
        }
        return List.of();
    }

    private List<Row> readJsonLines(Path path) {
        List<Row> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int order = 0;
            while ((line = reader.readLine()) != null) {
                order++;
                if (line.isBlank()) continue;
                try {
                    JsonNode node = mapper.readTree(line);
                    if (node == null || !node.isObject()) continue;
                    String rowBuildId = text(node, "build_id");
                    if (rowBuildId != null && expectedBuildId != null && !expectedBuildId.equals(rowBuildId)) {
                        throw new IllegalStateException("RAG build_id mismatch: expected " + expectedBuildId + ", got " + rowBuildId);
                    }
                    String relationshipStage = text(node, "relationship_stage");
                    String text = firstText(node, "text_zh", "text_jp", "text", "content", "response");
                    loaded.add(new Row(text, relationshipStage, order));
                } catch (IOException ignored) {
                    // A malformed derived row is skipped; canonical build checks remain fail-closed.
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return loaded;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private static Set<String> terms(String query) {
        Set<String> terms = new HashSet<>();
        if (query == null) return terms;
        var matcher = TERM_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) terms.add(matcher.group());
        return terms;
    }

    protected record Row(String text, String relationshipStage, int order) { }
    private record Scored(int score, int order, String text) { }

    private static String resolveBuildId(Path dataRoot) {
        String configured = System.getenv("MEGURI_BUILD_ID");
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (dataRoot != null) {
            try {
                JsonNode report = new ObjectMapper().readTree(Files.readString(dataRoot.resolve("build_report.json")));
                String buildId = report == null ? null : report.path("build_id").asText(null);
                if (buildId != null && !buildId.isBlank()) return buildId;
            } catch (Exception ignored) {
                // Fall back to the offline build id when no canonical report exists.
            }
        }
        return "meguri_local_mock";
    }
}
