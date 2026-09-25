package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExposurePlanner {
    private final CapabilityPolicy policy;
    private final int fastLimit;

    public ExposurePlanner(CapabilityPolicy policy, int fastLimit) {
        if (fastLimit < 1) throw new IllegalArgumentException("fastLimit must be positive");
        this.policy = policy;
        this.fastLimit = fastLimit;
    }

    public ExposurePlan plan(CapabilityRegistry.CapabilitySnapshot snapshot, ExposureContext context) {
        List<CapabilityRegistry.Grant> exposed = new ArrayList<>();
        snapshot.grants().stream()
                .sorted(Comparator.comparing(grant -> grant.descriptor().id()))
                .filter(grant -> policy.canExpose(grant.descriptor(), context).allowed())
                .limit(context.mode() == CapabilityDescriptor.Mode.FAST ? fastLimit : Long.MAX_VALUE)
                .forEach(exposed::add);
        return new ExposurePlan(snapshot.snapshotId(), context.turnId(), exposed);
    }

    public record ExposureContext(
            String turnId,
            String tenantId,
            String userId,
            String clientId,
            Set<String> scopes,
            CapabilityDescriptor.Mode mode,
            Set<String> intentCapabilities,
            int protocolVersion,
            boolean networkAllowed,
            CapabilityDescriptor.DataClassification maximumDataClassification) {
        public ExposureContext {
            turnId = CapabilityDescriptor.required(turnId, "turnId");
            tenantId = CapabilityDescriptor.required(tenantId, "tenantId");
            userId = CapabilityDescriptor.required(userId, "userId");
            clientId = CapabilityDescriptor.required(clientId, "clientId");
            scopes = CapabilityDescriptor.strings(scopes, "scopes");
            mode = mode == null ? CapabilityDescriptor.Mode.BALANCED : mode;
            intentCapabilities = intentCapabilities == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(intentCapabilities));
            if (protocolVersion < 1) throw new IllegalArgumentException("protocolVersion must be positive");
            maximumDataClassification = maximumDataClassification == null
                    ? CapabilityDescriptor.DataClassification.INTERNAL : maximumDataClassification;
        }
    }

    public record ExposurePlan(
            String snapshotId, String turnId, List<CapabilityRegistry.Grant> grants) {
        public ExposurePlan {
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
        public CapabilityRegistry.Grant grant(String id) {
            return grants.stream().filter(item -> item.descriptor().id().equals(id)).findFirst().orElse(null);
        }
        public List<CapabilityDescriptor> descriptors() {
            return grants.stream().map(CapabilityRegistry.Grant::descriptor).toList();
        }
    }
}
