package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Canonical local permission request and server-granted intersection. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterProtocolPermissions(
        @JsonProperty("screen_read") boolean screenRead,
        boolean microphone,
        @JsonProperty("audio_playback") boolean audioPlayback,
        boolean notifications,
        @JsonProperty("formal_memory_write") boolean formalMemoryWrite) {

    public AdapterProtocolPermissions intersect(
            AdapterProtocolCapabilities effectiveCapabilities,
            boolean serverScreenRead,
            boolean serverFormalMemoryWrite) {
        return new AdapterProtocolPermissions(
                screenRead && effectiveCapabilities.screenContext() && serverScreenRead,
                microphone && effectiveCapabilities.voice(),
                audioPlayback && effectiveCapabilities.voice(),
                notifications,
                formalMemoryWrite && effectiveCapabilities.formalMemory()
                        && serverFormalMemoryWrite);
    }

    public ClientPermissions toBinding() {
        return new ClientPermissions(formalMemoryWrite, screenRead, false);
    }
}
