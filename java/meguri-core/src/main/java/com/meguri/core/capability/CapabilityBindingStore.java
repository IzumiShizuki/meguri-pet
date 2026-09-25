package com.meguri.core.capability;

import java.time.Instant;
import java.util.Optional;

/** Durable global activation state; per-Turn exposure remains separately frozen. */
public interface CapabilityBindingStore {
    Optional<Binding> findBinding(String capabilityId);

    void save(Binding binding);

    record Binding(
            String capabilityId,
            String version,
            boolean enabled,
            boolean draining,
            CapabilityDescriptor.Health health,
            Instant updatedAt) {
    }

    static CapabilityBindingStore noop() {
        return new CapabilityBindingStore() {
            @Override
            public Optional<Binding> findBinding(String capabilityId) {
                return Optional.empty();
            }

            @Override
            public void save(Binding binding) {
            }
        };
    }
}
