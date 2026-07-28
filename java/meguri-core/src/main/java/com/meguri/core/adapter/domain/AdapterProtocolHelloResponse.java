package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AdapterProtocolHelloResponse(
        @JsonProperty("selected_protocol_version") String selectedProtocolVersion,
        @JsonProperty("server_capabilities_revision") String serverCapabilitiesRevision,
        @JsonProperty("server_capabilities") AdapterProtocolCapabilities serverCapabilities,
        @JsonProperty("effective_capabilities") AdapterProtocolCapabilities effectiveCapabilities,
        @JsonProperty("granted_permissions") AdapterProtocolPermissions grantedPermissions,
        @JsonProperty("supported_extensions") List<String> supportedExtensions) {
    public AdapterProtocolHelloResponse {
        if (selectedProtocolVersion == null || selectedProtocolVersion.isBlank()
                || serverCapabilitiesRevision == null || serverCapabilitiesRevision.isBlank()
                || serverCapabilities == null || effectiveCapabilities == null
                || grantedPermissions == null) {
            throw new IllegalArgumentException("canonical hello response fields are required");
        }
        supportedExtensions = supportedExtensions == null
                ? List.of() : List.copyOf(supportedExtensions);
    }
}
