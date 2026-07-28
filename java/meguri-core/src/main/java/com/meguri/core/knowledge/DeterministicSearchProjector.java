package com.meguri.core.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline term and vector projection with stable output across processes. */
public final class DeterministicSearchProjector {
    public static final String EMBEDDING_MODEL = "meguri-feature-hash-v1";
    public static final int EMBEDDING_DIMENSIONS = 64;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private DeterministicSearchProjector() {
    }

    public static KnowledgeTermProjection terms(KnowledgeChunk chunk) {
        Map<String, Integer> frequencies = termFrequencies(chunk.content());
        int tokenCount = frequencies.values().stream().mapToInt(Integer::intValue).sum();
        return new KnowledgeTermProjection(
                chunk.id(), chunk.documentId(), chunk.documentVersionId(),
                chunk.acl(), frequencies, tokenCount);
    }

    public static KnowledgeVectorProjection vector(KnowledgeChunk chunk) {
        return new KnowledgeVectorProjection(
                chunk.id(), chunk.documentId(), chunk.documentVersionId(), chunk.acl(),
                EMBEDDING_MODEL, embedding(chunk.content()));
    }

    public static List<Double> embedding(String text) {
        Map<String, Integer> terms = termFrequencies(text);
        if (terms.isEmpty()) throw new IllegalArgumentException("embedding text has no searchable terms");
        double[] values = new double[EMBEDDING_DIMENSIONS];
        new TreeMap<>(terms).forEach((term, frequency) -> {
            byte[] digest = digest(term);
            int bucket = Short.toUnsignedInt(ByteBuffer.wrap(digest).getShort()) % EMBEDDING_DIMENSIONS;
            double sign = (digest[2] & 1) == 0 ? 1.0 : -1.0;
            values[bucket] += sign * (1.0 + Math.log(frequency));
        });
        double norm = Math.sqrt(java.util.Arrays.stream(values).map(value -> value * value).sum());
        if (norm == 0.0) throw new IllegalArgumentException("embedding norm is zero");
        ArrayList<Double> normalized = new ArrayList<>(EMBEDDING_DIMENSIONS);
        for (double value : values) normalized.add(value / norm);
        return List.copyOf(normalized);
    }

    public static Map<String, Integer> termFrequencies(String text) {
        String normalized = Normalizer.normalize(
                text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        LinkedHashMap<String, Integer> terms = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            add(terms, token);
            if (containsHan(token)) addHanNgrams(terms, token);
        }
        return Collections.unmodifiableMap(terms);
    }

    private static void addHanNgrams(Map<String, Integer> terms, String token) {
        int[] points = token.codePoints().toArray();
        for (int point : points) {
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
                add(terms, new String(Character.toChars(point)));
            }
        }
        for (int index = 0; index + 1 < points.length; index++) {
            if (Character.UnicodeScript.of(points[index]) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(points[index + 1]) == Character.UnicodeScript.HAN) {
                add(terms, new String(points, index, 2));
            }
        }
    }

    private static boolean containsHan(String token) {
        return token.codePoints().anyMatch(
                point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
    }

    private static void add(Map<String, Integer> terms, String term) {
        terms.merge(term, 1, Integer::sum);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
