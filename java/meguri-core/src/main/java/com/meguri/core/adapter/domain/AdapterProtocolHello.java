package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;

/** Canonical Client Hello. Unknown minor-version fields are intentionally ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterProtocolHello(
        @JsonProperty("protocol_versions") List<String> protocolVersions,
        AdapterIdentityContext identity,
        AdapterProtocolCapabilities capabilities,
        AdapterProtocolPermissions permissions,
        @JsonProperty("required_extensions") List<String> requiredExtensions,
        @JsonProperty("client_version") String clientVersion) {

    public AdapterProtocolHello {
        if (protocolVersions == null || protocolVersions.isEmpty()) {
            throw new IllegalArgumentException("protocol_versions must not be empty");
        }
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        for (String version : protocolVersions) {
            versions.add(ProtocolVersion.parse(version).toString());
        }
        if (versions.size() > 16) {
            throw new IllegalArgumentException("too many protocol_versions");
        }
        protocolVersions = List.copyOf(versions);
        if (identity == null || capabilities == null || permissions == null) {
            throw new IllegalArgumentException("identity, capabilities and permissions are required");
        }
        requiredExtensions = normalized(requiredExtensions);
        clientVersion = clientVersion == null || clientVersion.isBlank()
                ? "unknown" : clientVersion.trim();
    }

    private static List<String> normalized(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("required_extensions must not contain blanks");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }
}
