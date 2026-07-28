package com.meguri.core.capability;

import java.time.Instant;
import java.util.List;

public interface CapabilityRegistry {
    void enable(String id, String version);
    void disable(String id);
    void activate(String id, String version);
    void setHealth(String id, String version, CapabilityDescriptor.Health health);
    void drain(String id);
    CapabilitySnapshot snapshot();

    record CapabilitySnapshot(String snapshotId, Instant frozenAt, List<Grant> grants) {
        public CapabilitySnapshot {
            snapshotId = CapabilityDescriptor.required(snapshotId, "snapshotId");
            frozenAt = frozenAt == null ? Instant.now() : frozenAt;
            grants = grants == null ? List.of() : List.copyOf(grants);
        }

        public Grant grant(String capabilityId) {
            return grants.stream().filter(item -> item.descriptor().id().equals(capabilityId)).findFirst().orElse(null);
        }
    }

    record Grant(CapabilityDescriptor descriptor, CapabilityImplementation implementation) { }
}
