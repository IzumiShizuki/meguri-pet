package com.meguri.core.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultCapabilityRegistry implements CapabilityRegistry {
    private final CapabilityCatalog catalog;
    private final Map<String, String> active = new LinkedHashMap<>();
    private final Map<Key, CapabilityDescriptor.Health> health = new LinkedHashMap<>();

    public DefaultCapabilityRegistry(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    public synchronized void registerAndEnable(
            CapabilityDescriptor descriptor, CapabilityImplementation implementation) {
        catalog.register(descriptor, implementation);
        enable(descriptor.id(), descriptor.version());
    }

    @Override
    public synchronized void enable(String id, String version) {
        definition(id, version);
        active.putIfAbsent(id, version);
    }

    @Override
    public synchronized void disable(String id) {
        active.remove(CapabilityDescriptor.required(id, "id"));
    }

    @Override
    public synchronized void activate(String id, String version) {
        definition(id, version);
        active.put(CapabilityDescriptor.required(id, "id"), CapabilityDescriptor.required(version, "version"));
    }

    @Override
    public synchronized void setHealth(String id, String version, CapabilityDescriptor.Health value) {
        definition(id, version);
        health.put(new Key(id, version), value);
    }

    @Override
    public synchronized void drain(String id) {
        disable(id);
    }

    @Override
    public synchronized CapabilitySnapshot snapshot() {
        List<Grant> grants = new ArrayList<>();
        active.forEach((id, version) -> {
            CapabilityCatalog.Definition definition = definition(id, version);
            CapabilityDescriptor descriptor = definition.descriptor().withHealth(
                    health.getOrDefault(new Key(id, version), definition.descriptor().health()));
            grants.add(new Grant(descriptor, definition.implementation()));
        });
        grants.sort(Comparator.comparing(grant -> grant.descriptor().id()));
        return new CapabilitySnapshot(hash(grants), Instant.now(), grants);
    }

    private CapabilityCatalog.Definition definition(String id, String version) {
        return catalog.find(CapabilityDescriptor.required(id, "id"), CapabilityDescriptor.required(version, "version"))
                .orElseThrow(() -> new IllegalArgumentException("unknown capability version: " + id + "@" + version));
    }

    private static String hash(List<Grant> grants) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            grants.forEach(grant -> digest.update(grant.descriptor().toString().getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Key(String id, String version) { }
}
