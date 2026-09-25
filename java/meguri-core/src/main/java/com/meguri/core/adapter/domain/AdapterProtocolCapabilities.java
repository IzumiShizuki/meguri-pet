package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Set;

/** Canonical capability flags used by /v1/hello. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterProtocolCapabilities(
        boolean text,
        boolean voice,
        boolean sprite,
        @JsonProperty("screen_context") boolean screenContext,
        @JsonProperty("formal_memory") boolean formalMemory,
        boolean sse) {

    public static AdapterProtocolCapabilities serverDefaults() {
        return new AdapterProtocolCapabilities(true, true, true, true, true, true);
    }

    public AdapterProtocolCapabilities intersect(AdapterProtocolCapabilities server) {
        if (server == null) return new AdapterProtocolCapabilities(false, false, false, false, false, false);
        return new AdapterProtocolCapabilities(
                text && server.text,
                voice && server.voice,
                sprite && server.sprite,
                screenContext && server.screenContext,
                formalMemory && server.formalMemory,
                sse && server.sse);
    }

    public AdapterClientCapabilities toBinding() {
        return new AdapterClientCapabilities(
                sse, text, voice, sprite, screenContext, false,
                Set.of("expression", "voice_style", "animation"),
                List.of("zh-CN", "ja-JP"));
    }
}
