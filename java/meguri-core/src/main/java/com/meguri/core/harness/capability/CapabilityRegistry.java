package com.meguri.core.harness.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;

/**
 * Typed registry used to freeze one capability policy snapshot per turn.
 * Registration does not imply authorization; snapshots contain grants only.
 */
public final class CapabilityRegistry {
    public enum Kind { PROMPT_SKILL, RESOURCE, READ_TOOL, WRITE_TOOL, REMOTE_AGENT }
    public enum Effect { NONE, READ, WRITE, EXTERNAL }
    public enum Approval { NONE, RISK_BASED, ALWAYS }

    public record Descriptor(
            String id,
            String version,
            Kind kind,
            Effect effect,
            Approval approval,
            Duration timeout,
            int concurrencyLimit,
            String implementation) {
        public Descriptor(
                String id,
                Kind kind,
                Effect effect,
                Approval approval,
                Duration timeout,
                int concurrencyLimit,
                String implementation) {
            this(id, "1", kind, effect, approval, timeout, concurrencyLimit, implementation);
        }

        public Descriptor {
            id = required(id, "id");
            version = required(version, "version");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(approval, "approval");
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
            if (concurrencyLimit < 1) throw new IllegalArgumentException("concurrency_limit must be positive");
            implementation = required(implementation, "implementation");
        }
    }

    public record Snapshot(String version, Instant frozenAt, List<Descriptor> grants) {
        public Snapshot {
            version = required(version, "version");
            frozenAt = frozenAt == null ? Instant.now() : frozenAt;
            grants = grants == null ? List.of() : List.copyOf(grants);
            HashSet<String> ids = new HashSet<>();
            for (Descriptor grant : grants) {
                Objects.requireNonNull(grant, "snapshot grant");
                if (!ids.add(grant.id())) {
                    throw new IllegalArgumentException("snapshot contains duplicate capability: " + grant.id());
                }
            }
        }

        public List<String> grantedIds() {
            return grants.stream().map(Descriptor::id).toList();
        }
    }

    private final Map<DefinitionKey, Descriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, String> activeVersions = new LinkedHashMap<>();

    public synchronized void register(Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        DefinitionKey key = new DefinitionKey(descriptor.id(), descriptor.version());
        Descriptor existing = descriptors.putIfAbsent(key, descriptor);
        if (existing != null && !existing.equals(descriptor)) {
            throw new IllegalArgumentException(
                    "capability version already registered with a different descriptor: "
                            + descriptor.id() + "@" + descriptor.version());
        }
        activeVersions.putIfAbsent(descriptor.id(), descriptor.version());
    }

    /** Switches only the active binding; already frozen snapshots retain the old version. */
    public synchronized void activate(String capabilityId, String version) {
        DefinitionKey key = new DefinitionKey(
                required(capabilityId, "capabilityId"), required(version, "version"));
        if (!descriptors.containsKey(key)) {
            throw new IllegalArgumentException(
                    "unknown capability version: " + key.id() + "@" + key.version());
        }
        activeVersions.put(key.id(), key.version());
    }

    /** Stops exposing a capability to new snapshots without unloading its implementation. */
    public synchronized void disable(String capabilityId) {
        activeVersions.remove(required(capabilityId, "capabilityId"));
    }

    public synchronized Snapshot freeze() {
        List<Descriptor> grants = new ArrayList<>();
        activeVersions.forEach((id, version) -> {
            Descriptor descriptor = descriptors.get(new DefinitionKey(id, version));
            if (descriptor == null) {
                throw new IllegalStateException("active capability version is missing: " + id + "@" + version);
            }
            grants.add(descriptor);
        });
        grants.sort(Comparator.comparing(Descriptor::id));
        return new Snapshot(hash(grants), Instant.now(), grants);
    }

    private static String hash(List<Descriptor> descriptors) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Descriptor descriptor : descriptors) {
                digest.update(descriptor.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record DefinitionKey(String id, String version) { }
}
