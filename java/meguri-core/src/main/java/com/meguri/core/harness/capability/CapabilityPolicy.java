package com.meguri.core.harness.capability;

import com.meguri.core.harness.retrieval.RetrievalMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authorization seam kept separate from capability registration. */
public interface CapabilityPolicy {
    Decision authorize(CapabilityRegistry.Descriptor descriptor, ExecutionRequest request);

    record ExecutionRequest(
            String turnId,
            String principalId,
            String idempotencyKey,
            boolean approvalGranted,
            Map<String, Object> input,
            SandboxBudget sandbox,
            RetrievalMode retrievalMode) {
        public ExecutionRequest(
                String turnId,
                String principalId,
                String idempotencyKey,
                boolean approvalGranted,
                Map<String, Object> input,
                SandboxBudget sandbox) {
            this(turnId, principalId, idempotencyKey, approvalGranted, input, sandbox,
                    RetrievalMode.compatibleDefault());
        }

        public ExecutionRequest {
            if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turnId must not be blank");
            if (principalId == null || principalId.isBlank()) {
                throw new IllegalArgumentException("principalId must not be blank");
            }
            idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                    ? turnId : idempotencyKey.trim();
            input = immutableInput(input);
            retrievalMode = retrievalMode == null
                    ? RetrievalMode.compatibleDefault() : retrievalMode;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> immutableInput(Map<String, Object> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (key == null) throw new IllegalArgumentException("capability input key must not be null");
                copy.put(key, immutableValue(value));
            });
            return Collections.unmodifiableMap(copy);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    if (!(key instanceof String text)) {
                        throw new IllegalArgumentException("capability input object keys must be strings");
                    }
                    copy.put(text, immutableValue(item));
                });
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Iterable<?> values) {
                List<Object> copy = new ArrayList<>();
                values.forEach(item -> copy.add(immutableValue(item)));
                return Collections.unmodifiableList(copy);
            }
            if (value != null && value.getClass().isArray()) {
                List<Object> copy = new ArrayList<>();
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    copy.add(immutableValue(java.lang.reflect.Array.get(value, index)));
                }
                return Collections.unmodifiableList(copy);
            }
            return value;
        }
    }

    record SandboxBudget(
            Duration wallTime,
            int maxSteps,
            int maxToolCalls,
            long maxCostUnits,
            Set<String> allowedCapabilities,
            Set<String> allowedRoots,
            Set<String> allowedHosts,
            boolean processExecutionAllowed) {
        public SandboxBudget {
            if (wallTime == null || wallTime.isNegative() || wallTime.isZero()) {
                throw new IllegalArgumentException("sandbox wallTime must be positive");
            }
            if (maxToolCalls < 1 || maxToolCalls > 32) {
                throw new IllegalArgumentException("sandbox maxToolCalls must be within 1..32");
            }
            if (maxSteps < 1 || maxSteps > 16) {
                throw new IllegalArgumentException("sandbox maxSteps must be within 1..16");
            }
            if (maxCostUnits < 1) {
                throw new IllegalArgumentException("sandbox maxCostUnits must be positive");
            }
            allowedCapabilities = nonBlankSet(allowedCapabilities, "allowedCapabilities", true);
            allowedRoots = nonBlankSet(allowedRoots, "allowedRoots", false);
            allowedHosts = nonBlankSet(allowedHosts, "allowedHosts", false);
        }

        private static Set<String> nonBlankSet(Set<String> values, String field, boolean required) {
            Set<String> copy = values == null
                    ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
            if (required && copy.isEmpty()) {
                throw new IllegalArgumentException("sandbox " + field + " must not be empty");
            }
            if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("sandbox " + field + " must not contain blanks");
            }
            return copy;
        }
    }

    record Decision(boolean allowed, String reason) {
        public Decision {
            reason = reason == null || reason.isBlank() ? (allowed ? "allowed" : "denied") : reason;
        }

        public static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
