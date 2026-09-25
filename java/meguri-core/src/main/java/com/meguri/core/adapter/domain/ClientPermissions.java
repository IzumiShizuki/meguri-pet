package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientPermissions(
        @JsonProperty("formal_memory_allowed") boolean formalMemoryAllowed,
        @JsonProperty("screen_context_allowed") boolean screenContextAllowed,
        @JsonProperty("local_resource_metadata_allowed") boolean localResourceMetadataAllowed) {

    public ClientPermissions intersect(
            boolean serverFormalMemory,
            boolean serverScreenContext,
            boolean serverLocalResourceMetadata) {
        return new ClientPermissions(
                formalMemoryAllowed && serverFormalMemory,
                screenContextAllowed && serverScreenContext,
                localResourceMetadataAllowed && serverLocalResourceMetadata);
    }
}
