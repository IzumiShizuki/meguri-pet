package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCapabilityCatalog implements CapabilityCatalog {
    private final Map<Key, Definition> definitions = new LinkedHashMap<>();

    @Override
    public synchronized void register(CapabilityDescriptor descriptor, CapabilityImplementation implementation) {
        Definition definition = new Definition(descriptor, implementation);
        Key key = new Key(descriptor.id(), descriptor.version());
        Definition existing = definitions.putIfAbsent(key, definition);
        if (existing != null && !existing.descriptor().equals(descriptor)) {
            throw new IllegalArgumentException("capability version already has a different schema: " + key);
        }
    }

    @Override
    public synchronized Optional<Definition> find(String id, String version) {
        return Optional.ofNullable(definitions.get(new Key(id, version)));
    }

    @Override
    public synchronized List<Definition> definitions() {
        return List.copyOf(new ArrayList<>(definitions.values()));
    }

    @Override
    public synchronized void remove(String id, String version) {
        definitions.remove(new Key(
                CapabilityDescriptor.required(id, "id"),
                CapabilityDescriptor.required(version, "version")));
    }

    private record Key(String id, String version) { }
}
