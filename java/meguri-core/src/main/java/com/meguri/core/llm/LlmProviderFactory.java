package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Environment-driven provider selection matching the Python runtime flags. */
public final class LlmProviderFactory {
    private LlmProviderFactory() {}

    public static LlmProvider createFromEnvironment() {
        return createFromEnvironment(new ObjectMapper());
    }

    /** Short alias used by adapters ported from the Python factory. */
    public static LlmProvider createFromEnv(ObjectMapper mapper) {
        return createFromEnvironment(mapper);
    }

    public static LlmProvider createFromEnvironment(ObjectMapper mapper) {
        if (mapper == null) mapper = new ObjectMapper();
        String provider = env("MEGURI_LLM_PROVIDER", "mock").trim().toLowerCase();
        if (provider.equals("mock")) return new MockLlmProvider();
        if (!provider.equals("openai-compatible")) {
            throw new LlmConfigurationException("unsupported MEGURI_LLM_PROVIDER: " + provider);
        }
        String baseUrl = env("MEGURI_LLM_BASE_URL", "").trim();
        String model = env("MEGURI_LLM_MODEL", "").trim();
        validateUrl(baseUrl);
        if (model.isBlank()) throw new LlmConfigurationException("MEGURI_LLM_MODEL must not be empty");
        String inlineApiKey = env("MEGURI_LLM_API_KEY", "").trim();
        String keyFile = env("MEGURI_LLM_API_KEY_FILE", "").trim();
        if (!inlineApiKey.isBlank() && !keyFile.isBlank()) {
            throw new LlmConfigurationException("use MEGURI_LLM_API_KEY_FILE instead of inline MEGURI_LLM_API_KEY");
        }
        String apiKey = readApiKey(keyFile);
        if (apiKey.isBlank() && !inlineApiKey.isBlank()) {
            throw new LlmConfigurationException("MEGURI_LLM_API_KEY must not be used; configure MEGURI_LLM_API_KEY_FILE");
        }
        boolean loopback = isLoopback(baseUrl);
        if (!loopback && apiKey.isBlank()) throw new LlmConfigurationException("remote LLM endpoints require MEGURI_LLM_API_KEY_FILE");
        double timeout = parseDouble("MEGURI_LLM_TIMEOUT_SECONDS", 30);
        int maxConcurrency = parseInt("MEGURI_LLM_MAX_CONCURRENCY", 4);
        if (timeout <= 0 || maxConcurrency <= 0) throw new LlmConfigurationException("LLM timeout/concurrency must be positive");
        String format = env("MEGURI_LLM_RESPONSE_FORMAT", "json_schema");
        String effectiveKey = apiKey.isBlank() ? "meguri-loopback" : apiKey;
        OpenAiChatModel modelClient = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(effectiveKey)
                .modelName(model)
                .timeout(Duration.ofMillis((long) (timeout * 1000)))
                .strictJsonSchema(format.trim().equalsIgnoreCase("json_schema"))
                .build();
        return new LangChain4jLlmProvider(modelClient, mapper, readPrompt(), format, maxConcurrency, releaseHeaders());
    }

    private static String readPrompt() {
        Path root = resolveConfigRoot();
        Path prompt = root.resolve("meguri_system_prompt.txt");
        Path schema = root.resolve("meguri_response.schema.json");
        try {
            String promptText = Files.readString(prompt);
            if (promptText.isBlank()) throw new LlmConfigurationException("Meguri system prompt must not be empty");
            // Keep the checked-in JSON contract as an operator-visible guard even
            // though LangChain4j receives the equivalent typed schema below.
            var schemaNode = new ObjectMapper().readTree(Files.readString(schema));
            var additionalProperties = schemaNode == null ? null : schemaNode.get("additionalProperties");
            var required = schemaNode == null ? null : schemaNode.get("required");
            var requiredFields = required == null ? Set.<String>of() : new java.util.HashSet<String>();
            if (required != null && required.isArray()) required.forEach(item -> requiredFields.add(item.asText()));
            if (schemaNode == null || !schemaNode.isObject()
                    || !schemaNode.path("required").isArray()
                    || additionalProperties == null || !additionalProperties.isBoolean()
                    || additionalProperties.asBoolean()
                    || !requiredFields.equals(Set.of("reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"))) {
                throw new LlmConfigurationException("Meguri response schema is invalid");
            }
            return promptText;
        } catch (Exception ex) {
            if (ex instanceof LlmConfigurationException lce) throw lce;
            throw new LlmConfigurationException("Meguri LLM contract files are unavailable", ex);
        }
    }

    private static Path resolveConfigRoot() {
        String configured = env("MEGURI_CONFIG_ROOT", "").trim();
        if (!configured.isBlank()) return Path.of(configured);
        for (Path candidate : List.of(Path.of("configs"), Path.of("..", "..", "configs"), Path.of("..", "configs"))) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return Path.of("configs");
    }

    private static Map<String, String> releaseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        String modelId = optional(env("MEGURI_MODEL_REGISTRY_ID", ""));
        String baseRevision = optional(env("MEGURI_LLM_BASE_MODEL_REVISION", ""));
        String adapterRevision = optional(env("MEGURI_LLM_ADAPTER_REVISION", ""));
        String adapterSha = optional(env("MEGURI_LLM_ADAPTER_SHA256", ""));
        if (modelId != null) {
            if (baseRevision == null) throw new LlmConfigurationException("registered LLM releases require base identity metadata");
            boolean hasAdapter = adapterRevision != null || adapterSha != null;
            if (hasAdapter && (adapterRevision == null || adapterSha == null)) {
                throw new LlmConfigurationException("adapter-backed registered LLM releases require base and adapter identity metadata");
            }
            headers.put("X-Meguri-Model-Id", modelId);
            headers.put("X-Meguri-Base-Revision", baseRevision);
            if (hasAdapter) {
                headers.put("X-Meguri-Adapter-Revision", adapterRevision);
                headers.put("X-Meguri-Adapter-SHA256", adapterSha);
            }
        }
        return headers;
    }

    private static void validateUrl(String raw) {
        try {
            java.net.URI uri = java.net.URI.create(raw);
            if (!(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null) throw new IllegalArgumentException();
            if (!uri.getScheme().equals("https") && !isLoopback(raw)) throw new LlmConfigurationException("non-loopback LLM endpoints must use HTTPS");
        } catch (Exception ex) {
            if (ex instanceof LlmConfigurationException lce) throw lce;
            throw new LlmConfigurationException("MEGURI_LLM_BASE_URL must be an HTTP(S) URL", ex);
        }
    }

    private static boolean isLoopback(String raw) {
        try {
            String host = java.net.URI.create(raw).getHost();
            return host != null && (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("::1"));
        } catch (Exception ex) { return false; }
    }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null ? fallback : value; }
    private static String optional(String value) { return value == null || value.isBlank() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null") ? null : value.trim(); }
    private static String readApiKey(String keyFile) {
        if (keyFile == null || keyFile.isBlank()) return "";
        try {
            Path path = Path.of(keyFile);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                throw new LlmConfigurationException("MEGURI_LLM_API_KEY_FILE is unreadable or must be absolute");
            }
            if (Files.size(path) > 8192) {
                throw new LlmConfigurationException("MEGURI_LLM_API_KEY_FILE is unexpectedly large");
            }
            String value = Files.readString(path).trim();
            if (value.isBlank()) throw new LlmConfigurationException("MEGURI_LLM_API_KEY_FILE must not be empty");
            return value;
        } catch (LlmConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmConfigurationException("MEGURI_LLM_API_KEY_FILE is unavailable", ex);
        }
    }
    private static double parseDouble(String key, double fallback) { try { return Double.parseDouble(env(key, String.valueOf(fallback))); } catch (NumberFormatException ex) { throw new LlmConfigurationException(key + " must be a number", ex); } }
    private static int parseInt(String key, int fallback) { try { return Integer.parseInt(env(key, String.valueOf(fallback))); } catch (NumberFormatException ex) { throw new LlmConfigurationException(key + " must be an integer", ex); } }
}
