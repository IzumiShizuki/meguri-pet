package com.meguri.core.capability;

import java.util.List;
import java.util.Optional;

public interface CapabilityCatalog {
    void register(CapabilityDescriptor descriptor, CapabilityImplementation implementation);
    Optional<Definition> find(String id, String version);
    List<Definition> definitions();
    void remove(String id, String version);

    record Definition(CapabilityDescriptor descriptor, CapabilityImplementation implementation) {
        public Definition {
            if (descriptor == null || implementation == null) throw new IllegalArgumentException("definition is incomplete");
        }
    }
}
