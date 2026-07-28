package com.meguri.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record KnowledgeAcl(String tenantId, Set<String> principals) {
    public KnowledgeAcl {
        tenantId = required(tenantId, "tenantId");
        Objects.requireNonNull(principals, "principals");
        if (principals.isEmpty()) throw new IllegalArgumentException("principals must not be empty");
        TreeSet<String> normalized = new TreeSet<>();
        for (String principal : principals) normalized.add(required(principal, "principal"));
        principals = Collections.unmodifiableSet(normalized);
    }

    public String hash() {
        String canonical = tenantId + "\n" + String.join("\n", new TreeSet<>(principals));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
