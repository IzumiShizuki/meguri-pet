package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/** The four identities carried by Adapter Protocol v1. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterIdentityContext(
        @JsonProperty("meguri_user") MeguriUser meguriUser,
        @JsonProperty("platform_actor") PlatformActor platformActor,
        @JsonProperty("client_instance") ClientInstance clientInstance,
        Session session) {
    private static final Set<String> PROFILES =
            Set.of("airi", "astrbot", "desktop_pet", "website", "custom");

    public AdapterIdentityContext {
        if (meguriUser == null || platformActor == null
                || clientInstance == null || session == null) {
            throw new IllegalArgumentException("all four adapter identities are required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeguriUser(String id) {
        public MeguriUser {
            id = required(id, "meguri_user.id");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformActor(
            String platform,
            @JsonProperty("actor_id") String actorId) {
        public PlatformActor {
            platform = required(platform, "platform_actor.platform");
            actorId = required(actorId, "platform_actor.actor_id");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientInstance(String id, String profile) {
        public ClientInstance {
            id = required(id, "client_instance.id");
            profile = required(profile, "client_instance.profile").toLowerCase();
            if (!PROFILES.contains(profile)) {
                throw new IllegalArgumentException("unsupported client profile: " + profile);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(String id) {
        public Session {
            id = required(id, "session.id");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
