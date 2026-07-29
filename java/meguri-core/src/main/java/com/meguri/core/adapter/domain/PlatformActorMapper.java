package com.meguri.core.adapter.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Converts an adapter-provided opaque actor ID into the value persisted by Core. */
public final class PlatformActorMapper {
    private PlatformActorMapper() {
    }

    public static String hash(String platform, String actorId) {
        String canonical = required(platform, "platform") + "\0"
                + required(actorId, "actorId");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
