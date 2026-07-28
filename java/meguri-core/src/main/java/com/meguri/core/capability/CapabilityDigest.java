package com.meguri.core.capability;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Type-preserving canonical digest for capability requests and results. */
final class CapabilityDigest {
    private CapabilityDigest() {
    }

    static String sha256(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String result(CapabilityResult value) {
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("status", value.status().name());
        canonical.put("error_code", value.errorCode());
        canonical.put("data", value.data());
        canonical.put("display", value.display());
        canonical.put("source", Map.of(
                "provider", value.source().provider(),
                "trust", value.source().trust().name()));
        canonical.put("warnings", value.warnings());
        canonical.put("retryable", value.retryable());
        return sha256(canonical);
    }

    private static void update(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 'N');
            return;
        }
        if (value instanceof String text) {
            digest.update((byte) 'S');
            bytes(digest, text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            digest.update((byte) 'E');
            update(digest, enumValue.getDeclaringClass().getName());
            update(digest, enumValue.name());
            return;
        }
        if (value instanceof Boolean flag) {
            digest.update((byte) 'B');
            digest.update((byte) (flag ? 1 : 0));
            return;
        }
        if (value instanceof Number number) {
            digest.update((byte) 'D');
            bytes(digest, canonicalNumber(number).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            digest.update((byte) 'M');
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(
                            "capability digest object keys must be strings");
                }
                entries.add(new AbstractMap.SimpleImmutableEntry<>(text, item));
            });
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            length(digest, entries.size());
            entries.forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            });
            return;
        }
        if (value instanceof Iterable<?> values) {
            digest.update((byte) 'L');
            List<Object> items = new ArrayList<>();
            values.forEach(items::add);
            length(digest, items.size());
            items.forEach(item -> update(digest, item));
            return;
        }
        if (value.getClass().isArray()) {
            digest.update((byte) 'L');
            int count = Array.getLength(value);
            length(digest, count);
            for (int index = 0; index < count; index++) {
                update(digest, Array.get(value, index));
            }
            return;
        }
        digest.update((byte) 'O');
        update(digest, value.getClass().getName());
        update(digest, String.valueOf(value));
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Double && !Double.isFinite(number.doubleValue())
                || number instanceof Float && !Float.isFinite(number.floatValue())) {
            throw new IllegalArgumentException(
                    "capability digest numbers must be finite");
        }
        BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
        return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }

    private static void bytes(MessageDigest digest, byte[] value) {
        length(digest, value.length);
        digest.update(value);
    }

    private static void length(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
