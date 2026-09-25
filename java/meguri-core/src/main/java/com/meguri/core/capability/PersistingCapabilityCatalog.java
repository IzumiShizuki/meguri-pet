package com.meguri.core.capability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps executable callbacks process-local while persisting immutable
 * descriptor metadata for audit and recovery.
 */
public final class PersistingCapabilityCatalog implements CapabilityCatalog {
    private final InMemoryCapabilityCatalog delegate =
            new InMemoryCapabilityCatalog();
    private final Consumer<CapabilityDescriptor> persistence;

    public PersistingCapabilityCatalog(
            Consumer<CapabilityDescriptor> persistence) {
        this.persistence = Objects.requireNonNull(persistence);
    }

    @Override
    public synchronized void register(
            CapabilityDescriptor descriptor,
            CapabilityImplementation implementation) {
        persistence.accept(Objects.requireNonNull(descriptor));
        delegate.register(descriptor, implementation);
    }

    @Override
    public Optional<Definition> find(String id, String version) {
        return delegate.find(id, version);
    }

    @Override
    public List<Definition> definitions() {
        return delegate.definitions();
    }

    @Override
    public void remove(String id, String version) {
        // Historical descriptor rows remain immutable even after runtime drain.
        delegate.remove(id, version);
    }
}
