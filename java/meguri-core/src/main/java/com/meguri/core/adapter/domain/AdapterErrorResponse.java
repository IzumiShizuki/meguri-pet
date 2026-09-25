package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdapterErrorResponse(
        @JsonProperty("protocol_version") String protocolVersion,
        AdapterProtocolError error) {
    public AdapterErrorResponse {
        if (protocolVersion == null || protocolVersion.isBlank() || error == null) {
            throw new IllegalArgumentException("error response fields are required");
        }
    }
}
