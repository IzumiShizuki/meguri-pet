package com.meguri.core.react;

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

/** Type-preserving, map-order-independent digest for action and observation deduplication. */
public final class ActionDigest {
    private ActionDigest() {
    }

    public static String sha256(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 'N');
        } else if (value instanceof String text) {
            digest.update((byte) 'S');
            bytes(digest, text.getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Enum<?> enumValue) {
            digest.update((byte) 'E');
            update(digest, enumValue.getDeclaringClass().getName());
            update(digest, enumValue.name());
        } else if (value instanceof Boolean flag) {
            digest.update((byte) 'B');
            digest.update((byte) (flag ? 1 : 0));
        } else if (value instanceof Number number) {
            digest.update((byte) 'D');
            bytes(digest, canonicalNumber(number).getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Map<?, ?> map) {
            digest.update((byte) 'M');
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("digest object keys must be strings");
                }
                entries.add(new AbstractMap.SimpleImmutableEntry<>(text, item));
            });
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            length(digest, entries.size());
            entries.forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            });
        } else if (value instanceof Iterable<?> iterable) {
            digest.update((byte) 'L');
            List<Object> items = new ArrayList<>();
            iterable.forEach(items::add);
            length(digest, items.size());
            items.forEach(item -> update(digest, item));
        } else if (value.getClass().isArray()) {
            digest.update((byte) 'L');
            int count = Array.getLength(value);
            length(digest, count);
            for (int index = 0; index < count; index++) update(digest, Array.get(value, index));
        } else {
            digest.update((byte) 'O');
            update(digest, value.getClass().getName());
            update(digest, String.valueOf(value));
        }
    }

    private static String canonicalNumber(Number value) {
        if (value instanceof Double && !Double.isFinite(value.doubleValue())
                || value instanceof Float && !Float.isFinite(value.floatValue())) {
            throw new IllegalArgumentException("digest numbers must be finite");
        }
        BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
        return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }

    private static void bytes(MessageDigest digest, byte[] bytes) {
        length(digest, bytes.length);
        digest.update(bytes);
    }

    private static void length(MessageDigest digest, int length) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }
}
