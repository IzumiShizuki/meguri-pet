package com.meguri.core.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Startup configuration for input assistance, independent of the Agent planner. */
public final class InputModelCheckerFactory {
    private InputModelCheckerFactory() { }

    public static InputModelChecker create(Map<String, String> environment, ObjectMapper mapper) {
        String model = environment.getOrDefault("MEGURI_INPUT_CHECK_MODEL", "").trim();
        String enabled = environment.getOrDefault("MEGURI_INPUT_CHECK_ENABLED", "true").trim();
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("MEGURI_INPUT_CHECK_ENABLED must be true or false");
        }
        if (model.isBlank() || !Boolean.parseBoolean(enabled)) return InputModelChecker.disabled();
        String base = environment.getOrDefault("MEGURI_INPUT_CHECK_BASE_URL",
                environment.getOrDefault("MEGURI_LLM_BASE_URL", "")).trim();
        if (base.isBlank()) throw new IllegalArgumentException("input-check model requires a base URL");
        if (!environment.getOrDefault("MEGURI_INPUT_CHECK_API_KEY", "").isBlank()) {
            throw new IllegalArgumentException("use MEGURI_INPUT_CHECK_API_KEY_FILE for input-check credentials");
        }
        String keyFile = environment.getOrDefault("MEGURI_INPUT_CHECK_API_KEY_FILE",
                environment.getOrDefault("MEGURI_LLM_API_KEY_FILE", "")).trim();
        String key = secret(keyFile);
        Duration timeout = Duration.ofMillis(integer(environment, "MEGURI_INPUT_CHECK_TIMEOUT_MS", 750, 1, 5000));
        Duration queue = Duration.ofMillis(integer(environment, "MEGURI_INPUT_CHECK_QUEUE_TIMEOUT_MS", 50, 0, 500));
        int concurrency = integer(environment, "MEGURI_INPUT_CHECK_MAX_CONCURRENCY", 2, 1, 32);
        var config = new InputModelChecker.Config(true, base, model, key, timeout, queue, concurrency, 8192);
        return new InputModelChecker(config, mapper, HttpClient.newBuilder().connectTimeout(timeout).build(), Clock.systemUTC());
    }

    private static String secret(String path) {
        if (path.isBlank()) return "";
        try {
            Path file = Path.of(path);
            if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > 8192) {
                throw new IllegalArgumentException("input-check secret file must be absolute and readable");
            }
            String value = Files.readString(file).trim();
            if (value.isBlank()) throw new IllegalArgumentException("input-check secret file must not be empty");
            return value;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("input-check secret file is unreadable");
        }
    }

    private static int integer(Map<String, String> env, String key, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback)).trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " is outside its supported range");
        }
    }
}
