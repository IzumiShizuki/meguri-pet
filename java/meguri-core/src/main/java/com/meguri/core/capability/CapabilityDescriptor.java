package com.meguri.core.capability;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CapabilityDescriptor(
        String id,
        String version,
        Kind kind,
        String owner,
        Schema inputSchema,
        Schema outputSchema,
        Set<String> scopes,
        SideEffect sideEffect,
        ApprovalRequirement approval,
        Duration timeout,
        RetryPolicy retry,
        ConcurrencyPolicy concurrency,
        Set<Mode> allowedModes,
        DataClassification dataClassification,
        ResultTrust resultTrust,
        String implementationRef,
        Health health,
        boolean deprecated,
        int minimumProtocol,
        NetworkPolicy network,
        Set<String> requiredSecrets,
        CostPolicy cost,
        CachePolicy cache,
        IdempotencyPolicy idempotency) {

    public enum Kind { PROMPT_SKILL, RESOURCE, READ_TOOL, WRITE_TOOL, REMOTE_AGENT }
    public enum SideEffect { NONE, READ, WRITE, EXTERNAL }
    public enum ApprovalRequirement { NONE, RISK_BASED, ALWAYS }
    public enum Mode { FAST, BALANCED, DEEP }
    public enum DataClassification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
    public enum ResultTrust { TRUSTED_LOCAL, UNTRUSTED_EXTERNAL }
    public enum Health { HEALTHY, DEGRADED, UNHEALTHY }
    public enum ValueType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY }

    public CapabilityDescriptor {
        id = required(id, "id");
        version = required(version, "version");
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("id has an invalid format");
        }
        if (!version.matches("[a-zA-Z0-9][a-zA-Z0-9._+-]{0,63}")) {
            throw new IllegalArgumentException("version has an invalid format");
        }
        owner = required(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        inputSchema = inputSchema == null ? Schema.empty() : inputSchema;
        outputSchema = outputSchema == null ? Schema.empty() : outputSchema;
        scopes = strings(scopes, "scopes");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(approval, "approval");
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        retry = retry == null ? RetryPolicy.none() : retry;
        concurrency = concurrency == null ? new ConcurrencyPolicy(1) : concurrency;
        allowedModes = allowedModes == null || allowedModes.isEmpty()
                ? Set.of(Mode.BALANCED, Mode.DEEP) : Set.copyOf(allowedModes);
        Objects.requireNonNull(dataClassification, "dataClassification");
        Objects.requireNonNull(resultTrust, "resultTrust");
        implementationRef = required(implementationRef, "implementationRef");
        Objects.requireNonNull(health, "health");
        if (minimumProtocol < 1) throw new IllegalArgumentException("minimumProtocol must be positive");
        network = network == null ? NetworkPolicy.denied() : network;
        requiredSecrets = strings(requiredSecrets, "requiredSecrets");
        cost = cost == null ? CostPolicy.free() : cost;
        cache = cache == null ? CachePolicy.disabled() : cache;
        idempotency = idempotency == null ? IdempotencyPolicy.none() : idempotency;

        if (kind == Kind.WRITE_TOOL && sideEffect != SideEffect.WRITE) {
            throw new IllegalArgumentException("WRITE_TOOL must declare WRITE side effect");
        }
        if (kind == Kind.PROMPT_SKILL && sideEffect != SideEffect.NONE) {
            throw new IllegalArgumentException("PROMPT_SKILL cannot have side effects");
        }
        if (resultTrust == ResultTrust.UNTRUSTED_EXTERNAL && kind == Kind.PROMPT_SKILL) {
            throw new IllegalArgumentException("external prompt cannot be promoted to a prompt skill");
        }
        if (sideEffect == SideEffect.WRITE && retry.maxAttempts() > 1 && !idempotency.required()) {
            throw new IllegalArgumentException("writes cannot retry without required idempotency");
        }
    }

    public CapabilityDescriptor withHealth(Health newHealth) {
        return new CapabilityDescriptor(id, version, kind, owner, inputSchema, outputSchema, scopes, sideEffect,
                approval, timeout, retry, concurrency, allowedModes, dataClassification, resultTrust,
                implementationRef, newHealth, deprecated, minimumProtocol, network, requiredSecrets, cost, cache,
                idempotency);
    }

    public record Schema(
            Map<String, ValueType> properties,
            Set<String> required,
            boolean additionalProperties,
            Set<String> sensitiveFields) {
        public Schema {
            properties = properties == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            properties.keySet().forEach(key -> CapabilityDescriptor.required(key, "schema property"));
            required = strings(required, "schema.required");
            sensitiveFields = strings(sensitiveFields, "schema.sensitiveFields");
            if (!properties.keySet().containsAll(required)) {
                throw new IllegalArgumentException("required schema fields must be declared");
            }
            if (!properties.keySet().containsAll(sensitiveFields)) {
                throw new IllegalArgumentException("sensitive schema fields must be declared");
            }
        }

        public static Schema empty() {
            return new Schema(Map.of(), Set.of(), false, Set.of());
        }

        public void validate(Map<String, Object> value, String label) {
            Map<String, Object> input = value == null ? Map.of() : value;
            for (String key : required) {
                if (!input.containsKey(key) || input.get(key) == null) {
                    throw new SchemaViolation(label + " missing required field: " + key);
                }
            }
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                ValueType expected = properties.get(entry.getKey());
                if (expected == null) {
                    if (!additionalProperties) throw new SchemaViolation(label + " has unknown field: " + entry.getKey());
                } else if (!matches(expected, entry.getValue())) {
                    throw new SchemaViolation(label + " field has wrong type: " + entry.getKey());
                }
            }
        }

        private static boolean matches(ValueType type, Object value) {
            if (value == null) return true;
            return switch (type) {
                case STRING -> value instanceof String;
                case NUMBER -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof Iterable<?> || value.getClass().isArray();
            };
        }
    }

    public record RetryPolicy(int maxAttempts, Duration backoff) {
        public RetryPolicy {
            if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("maxAttempts must be 1..5");
            backoff = backoff == null ? Duration.ZERO : backoff;
            if (backoff.isNegative()) throw new IllegalArgumentException("backoff must not be negative");
        }
        public static RetryPolicy none() { return new RetryPolicy(1, Duration.ZERO); }
    }

    public record ConcurrencyPolicy(int bulkheadLimit) {
        public ConcurrencyPolicy {
            if (bulkheadLimit < 1 || bulkheadLimit > 128) {
                throw new IllegalArgumentException("bulkheadLimit must be 1..128");
            }
        }
    }

    public record NetworkPolicy(boolean allowed, Set<String> allowedHosts) {
        public NetworkPolicy {
            allowedHosts = strings(allowedHosts, "allowedHosts");
            if (!allowed && !allowedHosts.isEmpty()) throw new IllegalArgumentException("denied network cannot allow hosts");
        }
        public static NetworkPolicy denied() { return new NetworkPolicy(false, Set.of()); }
    }

    public record CostPolicy(long estimatedUnits, long maximumUnits) {
        public CostPolicy {
            if (estimatedUnits < 0 || maximumUnits < estimatedUnits) {
                throw new IllegalArgumentException("invalid cost policy");
            }
        }
        public static CostPolicy free() { return new CostPolicy(0, 0); }
    }

    public record CachePolicy(boolean cacheable, Duration ttl) {
        public CachePolicy {
            ttl = ttl == null ? Duration.ZERO : ttl;
            if (ttl.isNegative() || (cacheable && ttl.isZero())) throw new IllegalArgumentException("invalid cache ttl");
        }
        public static CachePolicy disabled() { return new CachePolicy(false, Duration.ZERO); }
    }

    public record IdempotencyPolicy(boolean supported, boolean required) {
        public IdempotencyPolicy {
            if (required && !supported) throw new IllegalArgumentException("required idempotency must be supported");
        }
        public static IdempotencyPolicy none() { return new IdempotencyPolicy(false, false); }
    }

    public static final class SchemaViolation extends IllegalArgumentException {
        public SchemaViolation(String message) { super(message); }
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    static Set<String> strings(Set<String> values, String field) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) copy.add(required(value, field));
        return Collections.unmodifiableSet(copy);
    }
}
