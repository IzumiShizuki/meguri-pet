package com.meguri.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.RuntimeState;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, no-download hybrid retriever for the reviewed Meguri JSONL
 * exports. It applies language and relationship hard filters before ranking,
 * combines CJK n-grams/ASCII terms with reviewed intent aliases, and returns
 * no result when relevance is too weak. A future embedding retriever can still
 * be installed behind {@link RagProvider} without changing the turn contract.
 */
public class CanonicalRagRetriever implements RagProvider {
    private static final Pattern LATIN_TERM = Pattern.compile("[\\p{IsLatin}\\p{N}_]+");
    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;
    private static final double MIN_QUERY_COVERAGE = 0.34d;
    private static final double MIN_SCORE = 1.0d;
    private static final double MIN_ANCHOR_IDF = 3.5d;
    private static final double NEAR_DUPLICATE_JACCARD = 0.82d;
    private static final int MAX_SNIPPET_UTTERANCES = 2;
    private static final int MAX_SNIPPET_CHARS = 500;
    private static final String CJK_STOP_CHARS =
            "我你他她它的是了在有也就都而及与着被把让给会能可很吗呢啊呀哦吧么这那个一不";

    private final ObjectMapper mapper;
    private final List<Row> rows;
    private final String expectedBuildId;
    private final List<AliasConcept> aliases;
    private final CorpusStats zhStats;
    private final CorpusStats jaStats;

    public CanonicalRagRetriever(Path dataRoot) {
        this(dataRoot, new ObjectMapper(), resolveBuildId(dataRoot));
    }

    public CanonicalRagRetriever(Path dataRoot, ObjectMapper mapper, String expectedBuildId) {
        this(dataRoot, mapper, expectedBuildId, resolveAliasesPath(dataRoot));
    }

    CanonicalRagRetriever(Path dataRoot, ObjectMapper mapper, String expectedBuildId, Path aliasesPath) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.expectedBuildId = expectedBuildId == null || expectedBuildId.isBlank() ? null : expectedBuildId;
        this.aliases = loadAliases(aliasesPath);
        this.rows = List.copyOf(loadRows(dataRoot));
        this.zhStats = buildCorpusStats(Language.ZH);
        this.jaStats = buildCorpusStats(Language.JA);
    }

    @Override
    public List<String> search(String query, RuntimeState state, int limit) {
        if (limit <= 0 || rows.isEmpty() || state == null || query == null || query.isBlank()) {
            return List.of();
        }
        String relationship = state.getRelationshipProfile() == null
                ? null : state.getRelationshipProfile().value();
        if (relationship == null || relationship.isBlank()) return List.of();

        Language language = Language.detect(query);
        CorpusStats stats = language == Language.JA ? jaStats : zhStats;
        TokenProfile queryProfile = profile(query, language);
        if (queryProfile.frequencies().isEmpty()) return List.of();

        double queryWeight = queryProfile.frequencies().keySet().stream()
                .mapToDouble(term -> stats.idf(term)).sum();
        double maxQueryIdf = queryProfile.frequencies().keySet().stream()
                .mapToDouble(stats::idf).max().orElse(0.0d);
        double anchorFloor = Math.max(MIN_ANCHOR_IDF, maxQueryIdf * 0.75d);
        Set<String> anchors = new HashSet<>();
        for (String term : queryProfile.frequencies().keySet()) {
            if (!term.startsWith("concept:") && stats.idf(term) >= anchorFloor) anchors.add(term);
        }
        String compactQuery = compact(query);

        List<Scored> scored = new ArrayList<>();
        for (Row row : rows) {
            if (!relationship.equals(row.relationshipStage())) continue;
            String text = row.text(language);
            if (text.isBlank()) continue;
            TokenProfile document = profile(text, language);
            Set<String> matched = new HashSet<>(queryProfile.frequencies().keySet());
            matched.retainAll(document.frequencies().keySet());
            if (matched.isEmpty()) continue;

            boolean conceptMatch = matched.stream().anyMatch(term -> term.startsWith("concept:"));
            boolean anchorMatch = matched.stream().anyMatch(anchors::contains);
            boolean exactPhrase = compactQuery.length() >= 2 && compact(text).contains(compactQuery);
            if (!conceptMatch && !anchorMatch && !exactPhrase) continue;

            double matchedWeight = matched.stream().mapToDouble(stats::idf).sum();
            double coverage = queryWeight <= 0.0d ? 0.0d : matchedWeight / queryWeight;
            if (!conceptMatch && !exactPhrase && coverage < MIN_QUERY_COVERAGE) continue;

            double score = bm25(matched, document, stats);
            if (conceptMatch) score += 4.0d;
            if (exactPhrase) score += 4.0d;
            if (score < MIN_SCORE) continue;
            String snippet = snippet(text, queryProfile, language, stats);
            if (!snippet.isBlank()) {
                scored.add(new Scored(score, coverage, row.order(), row.sceneId(), snippet,
                        profile(snippet, language).frequencies().keySet()));
            }
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::coverage).reversed())
                .thenComparingInt(Scored::order));
        List<String> selected = new ArrayList<>();
        Set<String> scenes = new HashSet<>();
        Set<String> exact = new HashSet<>();
        List<Set<String>> selectedTerms = new ArrayList<>();
        for (Scored candidate : scored) {
            if (!candidate.sceneId().isBlank() && !scenes.add(candidate.sceneId())) continue;
            String normalized = compact(candidate.text());
            if (!exact.add(normalized)) continue;
            boolean nearDuplicate = selectedTerms.stream()
                    .anyMatch(existing -> jaccard(existing, candidate.terms()) >= NEAR_DUPLICATE_JACCARD);
            if (nearDuplicate) continue;
            selected.add(candidate.text());
            selectedTerms.add(candidate.terms());
            if (selected.size() >= limit) break;
        }
        return List.copyOf(selected);
    }

    public int size() {
        return rows.size();
    }

    private double bm25(Set<String> matched, TokenProfile document, CorpusStats stats) {
        double lengthRatio = stats.averageLength() <= 0.0d
                ? 1.0d : document.length() / stats.averageLength();
        double score = 0.0d;
        for (String term : matched) {
            int frequency = document.frequencies().getOrDefault(term, 0);
            if (frequency <= 0) continue;
            double denominator = frequency + BM25_K1 * (1.0d - BM25_B + BM25_B * lengthRatio);
            score += stats.idf(term) * (frequency * (BM25_K1 + 1.0d)) / denominator;
        }
        return score;
    }

    private String snippet(String text, TokenProfile query, Language language, CorpusStats stats) {
        List<String> utterances = utterances(text);
        List<UtteranceScore> ranked = new ArrayList<>();
        Set<String> queryConcepts = query.frequencies().keySet().stream()
                .filter(term -> term.startsWith("concept:"))
                .collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < utterances.size(); index++) {
            String utterance = utterances.get(index);
            TokenProfile candidate = profile(utterance, language);
            Set<String> matched = new HashSet<>(query.frequencies().keySet());
            matched.retainAll(candidate.frequencies().keySet());
            if (!queryConcepts.isEmpty() && matched.stream().noneMatch(queryConcepts::contains)) continue;
            double score = matched.stream().mapToDouble(stats::idf).sum();
            if (score > 0.0d) ranked.add(new UtteranceScore(index, score, utterance));
        }
        ranked.sort(Comparator.comparingDouble(UtteranceScore::score).reversed()
                .thenComparingInt(UtteranceScore::index));
        double bestScore = ranked.isEmpty() ? 0.0d : ranked.getFirst().score();
        List<UtteranceScore> chosen = ranked.stream()
                .filter(candidate -> candidate.score() >= bestScore * 0.65d)
                .limit(MAX_SNIPPET_UTTERANCES)
                .sorted(Comparator.comparingInt(UtteranceScore::index)).toList();
        String value = String.join("\n", chosen.stream().map(UtteranceScore::text).toList()).trim();
        if (value.length() > MAX_SNIPPET_CHARS) value = value.substring(0, MAX_SNIPPET_CHARS).trim();
        return value;
    }

    private static List<String> utterances(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isBlank()) continue;
            boolean speakerLine = line.startsWith("爱莉:") || line.startsWith("爱莉：")
                    || line.startsWith("メグリ:") || line.startsWith("メグリ：");
            if (speakerLine && !current.isEmpty()) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(' ');
            current.append(line);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private TokenProfile profile(String value, Language language) {
        Map<String, Integer> frequencies = tokenize(value);
        String normalized = normalizeForSearch(value);
        for (AliasConcept alias : aliases) {
            if (alias.matches(normalized, language)) frequencies.merge("concept:" + alias.id(), 1, Integer::sum);
        }
        int length = frequencies.values().stream().mapToInt(Integer::intValue).sum();
        return new TokenProfile(Map.copyOf(frequencies), length);
    }

    private static Map<String, Integer> tokenize(String value) {
        String normalized = normalizeForSearch(value);
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        var matcher = LATIN_TERM.matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() >= 2) frequencies.merge("word:" + term, 1, Integer::sum);
        }

        List<Integer> run = new ArrayList<>();
        normalized.codePoints().forEach(codePoint -> {
            if (isCjkLike(codePoint)) {
                run.add(codePoint);
            } else {
                addCjkTerms(run, frequencies);
                run.clear();
            }
        });
        addCjkTerms(run, frequencies);
        return frequencies;
    }

    private static void addCjkTerms(List<Integer> run, Map<String, Integer> frequencies) {
        if (run.isEmpty()) return;
        for (int codePoint : run) {
            String value = new String(Character.toChars(codePoint));
            if (!CJK_STOP_CHARS.contains(value)) frequencies.merge("char:" + value, 1, Integer::sum);
        }
        for (int width : List.of(2, 3)) {
            for (int start = 0; start + width <= run.size(); start++) {
                StringBuilder value = new StringBuilder();
                for (int offset = 0; offset < width; offset++) {
                    value.appendCodePoint(run.get(start + offset));
                }
                frequencies.merge("gram:" + value, 1, Integer::sum);
            }
        }
    }

    private CorpusStats buildCorpusStats(Language language) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        int totalLength = 0;
        int documents = 0;
        for (Row row : rows) {
            String text = row.text(language);
            if (text.isBlank()) continue;
            TokenProfile profile = profile(text, language);
            profile.frequencies().keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            totalLength += profile.length();
            documents++;
        }
        double averageLength = documents == 0 ? 0.0d : (double) totalLength / documents;
        return new CorpusStats(Map.copyOf(documentFrequency), documents, averageLength);
    }

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
                    if (expectedBuildId != null && !expectedBuildId.equals(rowBuildId)) {
                        throw new IllegalStateException("RAG build_id mismatch: expected " + expectedBuildId + ", got " + rowBuildId);
                    }
                    String textZh = firstText(node, "text_zh", "text", "content", "response");
                    String textJa = firstText(node, "text_jp", "text", "content", "response");
                    if (textZh.isBlank() && textJa.isBlank()) continue;
                    loaded.add(new Row(textZh, textJa, valueOrEmpty(text(node, "relationship_stage")),
                            valueOrEmpty(text(node, "scene_id")), valueOrEmpty(text(node, "chunk_id")), order));
                } catch (IOException ignored) {
                    // A malformed derived row is skipped; canonical build checks remain fail-closed.
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return loaded;
    }

    private List<AliasConcept> loadAliases(Path aliasesPath) {
        if (aliasesPath == null || !Files.isRegularFile(aliasesPath)) return List.of();
        try {
            JsonNode root = mapper.readTree(Files.readString(aliasesPath, StandardCharsets.UTF_8));
            JsonNode concepts = root == null ? null : root.path("concepts");
            if (concepts == null || !concepts.isArray()) {
                throw new IllegalStateException("Meguri RAG alias config must contain a concepts array");
            }
            List<AliasConcept> result = new ArrayList<>();
            for (JsonNode concept : concepts) {
                String id = valueOrEmpty(text(concept, "id"));
                if (id.isBlank()) throw new IllegalStateException("Meguri RAG alias concept id is required");
                result.add(new AliasConcept(id, stringList(concept.path("zh")), stringList(concept.path("ja"))));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Meguri RAG alias config is unavailable", error);
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) result.add(normalizeForSearch(item.asText()));
        });
        return List.copyOf(result);
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

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeForSearch(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("爱莉:", " ").replace("爱莉：", " ")
                .replace("メグリ:", " ").replace("メグリ：", " ")
                .replace("哥哥", " ").replace("兄さん", " ");
    }

    private static String compact(String value) {
        StringBuilder compact = new StringBuilder();
        normalizeForSearch(value).codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if (Character.isLetterOrDigit(codePoint) && !CJK_STOP_CHARS.contains(character)) {
                compact.appendCodePoint(codePoint);
            }
        });
        return compact.toString();
    }

    private static boolean isCjkLike(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0.0d;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0d : (double) intersection.size() / union.size();
    }

    private static Path resolveAliasesPath(Path dataRoot) {
        String configured = System.getenv("MEGURI_RAG_QUERY_ALIASES_PATH");
        if (configured != null && !configured.isBlank()) return Path.of(configured.trim());
        List<Path> candidates = new ArrayList<>();
        if (dataRoot != null && dataRoot.getParent() != null && dataRoot.getParent().getParent() != null) {
            candidates.add(dataRoot.getParent().getParent().resolve("configs").resolve("meguri_rag_query_aliases.json"));
        }
        candidates.add(Path.of("configs", "meguri_rag_query_aliases.json"));
        candidates.add(Path.of("..", "..", "configs", "meguri_rag_query_aliases.json"));
        candidates.add(Path.of("..", "configs", "meguri_rag_query_aliases.json"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(candidates.getFirst());
    }

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

    private enum Language {
        ZH, JA;

        static Language detect(String query) {
            boolean japanese = query != null && query.codePoints().anyMatch(codePoint -> {
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                return script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA;
            });
            return japanese ? JA : ZH;
        }
    }

    private record Row(String textZh, String textJa, String relationshipStage,
                       String sceneId, String chunkId, int order) {
        String text(Language language) {
            String preferred = language == Language.JA ? textJa : textZh;
            String fallback = language == Language.JA ? textZh : textJa;
            return preferred == null || preferred.isBlank() ? valueOrEmpty(fallback) : preferred;
        }
    }

    private record AliasConcept(String id, List<String> zh, List<String> ja) {
        boolean matches(String normalized, Language language) {
            List<String> values = language == Language.JA ? ja : zh;
            return values.stream().anyMatch(normalized::contains);
        }
    }

    private record TokenProfile(Map<String, Integer> frequencies, int length) { }

    private record CorpusStats(Map<String, Integer> documentFrequency, int documents, double averageLength) {
        double idf(String term) {
            int frequency = documentFrequency.getOrDefault(term, 0);
            return Math.log(1.0d + (documents - frequency + 0.5d) / (frequency + 0.5d));
        }
    }

    private record Scored(double score, double coverage, int order, String sceneId,
                          String text, Set<String> terms) { }

    private record UtteranceScore(int index, double score, String text) { }
}
