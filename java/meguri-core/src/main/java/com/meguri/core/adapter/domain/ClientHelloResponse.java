package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ClientHelloResponse(
        @JsonProperty("selected_protocol_version") String selectedProtocolVersion,
        @JsonProperty("server_capabilities_revision") String serverCapabilitiesRevision,
        @JsonProperty("client_instance_id") String clientInstanceId,
        AdapterClientCapabilities capabilities,
        ClientPermissions permissions,
        List<String> degradations) {
    public ClientHelloResponse {
        if (selectedProtocolVersion == null || selectedProtocolVersion.isBlank()
                || serverCapabilitiesRevision == null || serverCapabilitiesRevision.isBlank()
                || clientInstanceId == null || clientInstanceId.isBlank()
                || capabilities == null || permissions == null) {
            throw new IllegalArgumentException("client hello response fields must be present");
        }
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
    }
}
