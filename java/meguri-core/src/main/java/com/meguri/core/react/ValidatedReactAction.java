package com.meguri.core.react;

import com.meguri.core.capability.CapabilityDescriptor;

import java.util.Objects;

public record ValidatedReactAction(
        ReactAction action,
        CapabilityDescriptor descriptor,
        String actionDigest) {

    public ValidatedReactAction {
        action = Objects.requireNonNull(action, "action");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        actionDigest = ReactValues.required(actionDigest, "actionDigest");
    }
}
