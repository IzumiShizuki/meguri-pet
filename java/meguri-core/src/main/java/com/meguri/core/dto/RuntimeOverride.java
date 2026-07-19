package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RuntimeOverride(
        Mode mode,
        @JsonProperty("relationship_profile") Relationship relationshipProfile,
        @JsonProperty("outfit_code") String outfitCode,
        @JsonProperty("expires_at") OffsetDateTime expiresAt) {
    @JsonCreator
    public RuntimeOverride {
        if (outfitCode != null && !SetOutfits.ALLOWED.contains(outfitCode)) {
            throw new IllegalArgumentException("outfit_code must be one of 01-06; 07 and 08 are disabled");
        }
        // OffsetDateTime cannot represent a timezone-less value, so deserialization is fail-closed.
    }

    public RuntimeOverride() { this(null, null, null, null); }

    public Mode getMode() { return mode; }
    public Relationship getRelationshipProfile() { return relationshipProfile; }
    public String getOutfitCode() { return outfitCode; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
