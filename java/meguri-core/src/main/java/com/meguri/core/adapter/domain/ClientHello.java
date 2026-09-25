package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientHello(
        @JsonProperty("protocol_version") String protocolVersion,
        Client client,
        AdapterClientCapabilities capabilities,
        ClientPermissions permissions,
        @JsonProperty("meguri_user_id") String meguriUserId,
        @JsonProperty("session_id") String sessionId) {
    private static final Set<String> CLIENT_IDS =
            Set.of("airi", "astrbot", "desktop_pet", "website", "custom");

    public ClientHello {
        ProtocolVersion.parse(protocolVersion);
        if (client == null) throw new IllegalArgumentException("client must be present");
        capabilities = capabilities == null ? AdapterClientCapabilities.defaults() : capabilities;
        permissions = permissions == null ? new ClientPermissions(false, false, false) : permissions;
        meguriUserId = optional(meguriUserId);
        sessionId = optional(sessionId);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Client(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_instance_id") String clientInstanceId,
            @JsonProperty("client_version") String clientVersion) {
        public Client {
            clientId = required(clientId, "client_id");
            if (!CLIENT_IDS.contains(clientId)) {
                throw new IllegalArgumentException("unsupported client_id: " + clientId);
            }
            clientInstanceId = required(clientInstanceId, "client_instance_id");
            clientVersion = required(clientVersion, "client_version");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
