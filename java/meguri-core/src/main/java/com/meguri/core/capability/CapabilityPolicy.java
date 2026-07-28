package com.meguri.core.capability;

public interface CapabilityPolicy {
    Decision canExpose(CapabilityDescriptor descriptor, ExposurePlanner.ExposureContext context);
    Decision canExecute(CapabilityDescriptor descriptor, ToolProposal proposal);

    record Decision(boolean allowed, String code) {
        public static Decision allow() { return new Decision(true, "ALLOWED"); }
        public static Decision deny(String code) { return new Decision(false, code); }
    }
}
