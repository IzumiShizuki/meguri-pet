package com.meguri.core.capability;

import java.util.Map;

public interface ResultNormalizer {
    CapabilityResult normalize(CapabilityDescriptor descriptor, Map<String, Object> raw);
}
