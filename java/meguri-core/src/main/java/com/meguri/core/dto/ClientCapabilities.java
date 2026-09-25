package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Capabilities advertised by a client adapter. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ClientCapabilities {
    private final boolean text;
    private final boolean sprite;
    private final boolean voice;
    private final boolean screenContext;

    public ClientCapabilities() {
        this(true, false, false, false);
    }

    public ClientCapabilities(boolean text, boolean sprite, boolean voice, boolean screenContext) {
        this.text = text;
        this.sprite = sprite;
        this.voice = voice;
        this.screenContext = screenContext;
    }

    @JsonCreator
    public ClientCapabilities(
            @JsonProperty("text") Boolean text,
            @JsonProperty("sprite") Boolean sprite,
            @JsonProperty("voice") Boolean voice,
            @JsonProperty("screen_context") Boolean screenContext) {
        this(text == null || text, sprite != null && sprite, voice != null && voice,
                screenContext != null && screenContext);
    }

    @JsonProperty("text")
    public boolean isText() { return text; }
    public boolean getText() { return text; }
    @JsonProperty("sprite")
    public boolean isSprite() { return sprite; }
    public boolean getSprite() { return sprite; }
    @JsonProperty("voice")
    public boolean isVoice() { return voice; }
    public boolean getVoice() { return voice; }
    @JsonProperty("screen_context")
    public boolean isScreenContext() { return screenContext; }
    public boolean getScreenContext() { return screenContext; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ClientCapabilities that)) return false;
        return text == that.text && sprite == that.sprite && voice == that.voice && screenContext == that.screenContext;
    }
    @Override public int hashCode() { return Objects.hash(text, sprite, voice, screenContext); }
    @Override public String toString() { return "ClientCapabilities[text=" + text + ", sprite=" + sprite + ", voice=" + voice + ", screenContext=" + screenContext + "]"; }
}
