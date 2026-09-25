package com.meguri.core.adapter.domain;

import java.util.Objects;

/** Parsed Adapter Protocol version used for deterministic compatibility checks. */
public record ProtocolVersion(int major, int minor) implements Comparable<ProtocolVersion> {
    public static final ProtocolVersion CURRENT = new ProtocolVersion(1, 0);

    public ProtocolVersion {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be positive");
        }
    }

    public static ProtocolVersion parse(String value) {
        if (value == null || !value.matches("[1-9][0-9]*\\.[0-9]+")) {
            throw new IllegalArgumentException("protocol_version must use major.minor syntax");
        }
        String[] components = value.split("\\.", -1);
        return new ProtocolVersion(
                Integer.parseInt(components[0]),
                Integer.parseInt(components[1]));
    }

    public boolean isCompatibleWith(ProtocolVersion server) {
        return server != null && major == server.major;
    }

    @Override
    public int compareTo(ProtocolVersion other) {
        Objects.requireNonNull(other, "other");
        int majorOrder = Integer.compare(major, other.major);
        return majorOrder == 0 ? Integer.compare(minor, other.minor) : majorOrder;
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
