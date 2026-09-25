package com.meguri.core.harness.capability;

import reactor.core.publisher.Mono;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import reactor.core.publisher.SignalType;

/** Applies frozen grants, policy, schema, concurrency, timeout, and receipts. */
public final class CapabilityExecutor {
    private final CapabilityPolicy policy;
    private final EffectLedger ledger;
    private final Map<SchemaKey, CapabilityInputSchema> schemas = new ConcurrentHashMap<>();
    private final Map<ConcurrencyKey, Semaphore> concurrency = new ConcurrentHashMap<>();

    public CapabilityExecutor(CapabilityPolicy policy, EffectLedger ledger) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public void registerSchema(String capabilityId, CapabilityInputSchema schema) {
        registerSchema(capabilityId, "*", schema);
    }

    public void registerSchema(String capabilityId, String capabilityVersion, CapabilityInputSchema schema) {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("capabilityId must not be blank");
        }
        if (capabilityVersion == null || capabilityVersion.isBlank()) {
            throw new IllegalArgumentException("capabilityVersion must not be blank");
        }
        SchemaKey key = new SchemaKey(capabilityId.trim(), capabilityVersion.trim());
        CapabilityInputSchema existing = schemas.putIfAbsent(key, Objects.requireNonNull(schema, "schema"));
        if (existing != null && !existing.equals(schema)) {
            throw new IllegalArgumentException(
                    "capability schema is already registered: " + key.id() + "@" + key.version());
        }
    }

    public <T> Mono<T> execute(
            CapabilityRegistry.Snapshot snapshot,
            String capabilityId,
            CapabilityPolicy.ExecutionRequest request,
            Supplier<Mono<T>> operation) {
        return Mono.defer(() -> {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(operation, "operation");
            CapabilityRegistry.Descriptor descriptor = snapshot.grants().stream()
                    .filter(candidate -> candidate.id().equals(capabilityId))
                    .findFirst()
                    .orElseThrow(() -> new CapabilityExecutionException(
                            "capability is not granted in the frozen snapshot: " + capabilityId));
            CapabilityPolicy.Decision decision = policy.authorize(descriptor, request);
            if (!decision.allowed()) throw new CapabilityExecutionException(decision.reason());
            CapabilityInputSchema schema = schemas.get(new SchemaKey(capabilityId, descriptor.version()));
            if (schema == null) schema = schemas.get(new SchemaKey(capabilityId, "*"));
            if (schema == null) {
                throw new CapabilityExecutionException(
                        "capability input schema is not registered: "
                                + capabilityId + "@" + descriptor.version());
            }
            schema.validate(request.input());
            ConcurrencyKey concurrencyKey = new ConcurrencyKey(
                    descriptor.id(), descriptor.version(), descriptor.concurrencyLimit());
            Semaphore limit = concurrency.computeIfAbsent(concurrencyKey,
                    ignored -> new Semaphore(descriptor.concurrencyLimit()));
            if (!limit.tryAcquire()) {
                return Mono.error(new CapabilityExecutionException("capability concurrency limit exceeded"));
            }
            String inputHash = hash(request.input());
            EffectLedger.Claim claim;
            try {
                claim = ledger.begin(
                        request.turnId(), request.principalId(), capabilityId, descriptor.version(),
                        request.idempotencyKey(), descriptor.effect(), request.approvalGranted(), inputHash);
            } catch (RuntimeException error) {
                limit.release();
                return Mono.error(error);
            }
            EffectLedger.Receipt receipt = claim.receipt();
            if (!claim.created()) {
                limit.release();
                return Mono.error(new CapabilityExecutionException(
                        "effect idempotency scope already exists with status "
                                + receipt.status().name().toLowerCase(java.util.Locale.ROOT)));
            }
            java.time.Duration effectiveTimeout = descriptor.timeout();
            if (request.sandbox() != null && request.sandbox().wallTime().compareTo(effectiveTimeout) < 0) {
                effectiveTimeout = request.sandbox().wallTime();
            }
            return Mono.defer(() -> Objects.requireNonNull(
                            operation.get(), "capability operation returned null"))
                    .timeout(effectiveTimeout)
                    .doOnSuccess(value -> ledger.complete(receipt.receiptId(), hash(value)))
                    .doOnError(error -> ledger.fail(receipt.receiptId(), errorCode(error)))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            ledger.fail(receipt.receiptId(), "cancelled");
                        }
                        limit.release();
                    });
        });
    }

    public EffectLedger ledger() {
        return ledger;
    }

    private static String errorCode(Throwable error) {
        if (error == null) return "capability_failed";
        if (error instanceof java.util.concurrent.TimeoutException) return "timeout";
        return error.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private static String hash(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, value);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 'N');
            return;
        }
        if (value instanceof String text) {
            digest.update((byte) 'S');
            updateBytes(digest, text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (value instanceof Boolean flag) {
            digest.update((byte) 'B');
            digest.update((byte) (flag ? 1 : 0));
            return;
        }
        if (value instanceof Number number) {
            digest.update((byte) 'D');
            String canonical = canonicalNumber(number);
            updateBytes(digest, canonical.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            digest.update((byte) 'M');
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("capability digest object keys must be strings");
                }
                entries.add(new AbstractMap.SimpleImmutableEntry<>(text, item));
            });
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            updateLength(digest, entries.size());
            for (Map.Entry<String, Object> entry : entries) {
                updateDigest(digest, entry.getKey());
                updateDigest(digest, entry.getValue());
            }
            return;
        }
        if (value instanceof Iterable<?> values) {
            digest.update((byte) 'L');
            List<Object> items = new ArrayList<>();
            values.forEach(items::add);
            updateLength(digest, items.size());
            items.forEach(item -> updateDigest(digest, item));
            return;
        }
        if (value.getClass().isArray()) {
            digest.update((byte) 'L');
            int length = Array.getLength(value);
            updateLength(digest, length);
            for (int index = 0; index < length; index++) updateDigest(digest, Array.get(value, index));
            return;
        }
        digest.update((byte) 'O');
        updateDigest(digest, value.getClass().getName());
        updateDigest(digest, String.valueOf(value));
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Double && !Double.isFinite(number.doubleValue())
                || number instanceof Float && !Float.isFinite(number.floatValue())) {
            throw new IllegalArgumentException("capability digest numbers must be finite");
        }
        BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
        return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }

    private static void updateBytes(MessageDigest digest, byte[] value) {
        updateLength(digest, value.length);
        digest.update(value);
    }

    private static void updateLength(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private record SchemaKey(String id, String version) { }

    private record ConcurrencyKey(String id, String version, int limit) { }
}
