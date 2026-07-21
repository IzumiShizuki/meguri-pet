package com.meguri.core.input;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Result of the prefix-only input layer. It never invokes a tool by itself. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InputResolution(
        String kind,
        String raw,
        String message,
        String command,
        String arguments,
        String preview,
        String error) {

    public static InputResolution normal(String raw, String message) {
        return new InputResolution("normal", raw, message, null, null, null, null);
    }

    public static InputResolution command(String raw, String command, String arguments) {
        return new InputResolution("command", raw, null, command, arguments, null, null);
    }

    public static InputResolution preprocess(String raw, String expanded, String preview) {
        return new InputResolution("preprocess", raw, expanded, null, null, preview, null);
    }

    public static InputResolution error(String raw, String command, String error) {
        return new InputResolution("error", raw, null, command, null, null, error);
    }
}
