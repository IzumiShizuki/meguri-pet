package com.meguri.core.capability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DelegatingCapabilityImplementation {
    private final Map<String, CapabilityImplementation> callbacks = new ConcurrentHashMap<>();

    public void bind(String capabilityId, CapabilityImplementation callback) {
        callbacks.put(CapabilityDescriptor.required(capabilityId, "capabilityId"),
                java.util.Objects.requireNonNull(callback, "callback"));
    }

    public CapabilityImplementation implementation(String capabilityId) {
        String id = CapabilityDescriptor.required(capabilityId, "capabilityId");
        return (input, context) -> {
            CapabilityImplementation callback = callbacks.get(id);
            if (callback == null) {
                throw new IllegalStateException("no callback is bound for capability: " + id);
            }
            return callback.invoke(input, context);
        };
    }
}
