package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.metrics.PromptCacheMetricsRecorder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Environment-driven provider selection matching the Python runtime flags. */
public final class LlmProviderFactory {
    private LlmProviderFactory() {}

    public static LlmProvider createFromEnvironment() {
        return createFromEnvironment(new ObjectMapper(), PromptCacheMetricsRecorder.noop());
    }

    /** Short alias used by adapters ported from the Python factory. */
    public static LlmProvider createFromEnv(ObjectMapper mapper) {
        return createFromEnvironment(mapper);
    }

    public static LlmProvider createFromEnvironment(ObjectMapper mapper) {
        return createFromEnvironment(mapper, PromptCacheMetricsRecorder.noop());
    }

    public static LlmProvider createFromEnvironment(ObjectMapper mapper, PromptCacheMetricsRecorder metrics) {
        if (mapper == null) mapper = new ObjectMapper();
        if (metrics == null) metrics = PromptCacheMetricsRecorder.noop();
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
        String apiKey = readApiKey(keyFile, "MEGURI_LLM_API_KEY_FILE");
        if (apiKey.isBlank() && !inlineApiKey.isBlank()) {
            throw new LlmConfigurationException("MEGURI_LLM_API_KEY must not be used; configure MEGURI_LLM_API_KEY_FILE");
        }
        boolean loopback = isLoopback(baseUrl);
        if (!loopback && apiKey.isBlank()) throw new LlmConfigurationException("remote LLM endpoints require MEGURI_LLM_API_KEY_FILE");
        double timeout = parseDouble("MEGURI_LLM_TIMEOUT_SECONDS", 30);
        int maxConcurrency = parseInt("MEGURI_LLM_MAX_CONCURRENCY", 4);
        int maxTokens = parseInt("MEGURI_LLM_MAX_TOKENS", 1200);
        int promptTokenBudget = parseInt("MEGURI_LLM_PROMPT_TOKEN_BUDGET", 12000);
        String thinking = env("MEGURI_LLM_THINKING", "auto").trim().toLowerCase();
        String plannerModel = env("MEGURI_AGENT_PLANNER_MODEL", model).trim();
        String plannerKeyFile = env("MEGURI_AGENT_PLANNER_API_KEY_FILE", "").trim();
        String plannerApiKey = resolvePlannerApiKey(plannerKeyFile, apiKey);
        int plannerMaxTokens = parseInt("MEGURI_AGENT_PLANNER_MAX_TOKENS", 128);
        int plannerMaxConcurrency = parseInt("MEGURI_AGENT_PLANNER_MAX_CONCURRENCY", 1);
        int plannerQueueTimeoutMs = parseInt("MEGURI_AGENT_PLANNER_QUEUE_TIMEOUT_MS", 250);
        int plannerProviderTimeoutMs = parseInt("MEGURI_AGENT_PLANNER_PROVIDER_TIMEOUT_MS", 4000);
        int plannerDeadlineMs = parseInt("MEGURI_AGENT_PLANNER_DEADLINE_MS", 5000);
        String plannerThinking = env("MEGURI_AGENT_PLANNER_THINKING", "disabled").trim().toLowerCase();
        String multimodalFallbackModel = env("MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL", "").trim();
        String multimodalFallbackBaseUrl = env(
                "MEGURI_LLM_MULTIMODAL_FALLBACK_BASE_URL", baseUrl).trim();
        String multimodalFallbackKeyFile = env(
                "MEGURI_LLM_MULTIMODAL_FALLBACK_API_KEY_FILE", "").trim();
        String multimodalFallbackThinking = env(
                "MEGURI_LLM_MULTIMODAL_FALLBACK_THINKING", thinking).trim().toLowerCase();
        if (timeout <= 0 || maxConcurrency <= 0 || maxTokens <= 0 || promptTokenBudget < 256) {
            throw new LlmConfigurationException("LLM timeout/concurrency/max tokens must be positive");
        }
        if (plannerModel.isBlank() || plannerMaxTokens <= 0 || plannerMaxConcurrency <= 0
                || plannerQueueTimeoutMs < 0 || plannerProviderTimeoutMs <= 0
                || plannerDeadlineMs <= plannerProviderTimeoutMs) {
            throw new LlmConfigurationException(
                    "planner model/concurrency/tokens/timeouts must be valid and provider timeout below deadline");
        }
        if (!loopback && plannerApiKey.isBlank()) {
            throw new LlmConfigurationException(
                    "remote planner endpoints require MEGURI_AGENT_PLANNER_API_KEY_FILE or MEGURI_LLM_API_KEY_FILE");
        }
        if (!Set.of("auto", "enabled", "disabled").contains(thinking)) {
            throw new LlmConfigurationException("MEGURI_LLM_THINKING must be auto, enabled or disabled");
        }
        if (!Set.of("auto", "enabled", "disabled").contains(plannerThinking)) {
            throw new LlmConfigurationException(
                    "MEGURI_AGENT_PLANNER_THINKING must be auto, enabled or disabled");
        }
        if (!Set.of("auto", "enabled", "disabled").contains(multimodalFallbackThinking)) {
            throw new LlmConfigurationException(
                    "MEGURI_LLM_MULTIMODAL_FALLBACK_THINKING must be auto, enabled or disabled");
        }
        String format = env("MEGURI_LLM_RESPONSE_FORMAT", "json_schema");
        String effectiveKey = apiKey.isBlank() ? "meguri-loopback" : apiKey;
        String effectivePlannerKey = plannerApiKey.isBlank() ? "meguri-loopback" : plannerApiKey;
        var modelBuilder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(effectiveKey)
                .modelName(model)
                .maxTokens(maxTokens)
                .timeout(Duration.ofMillis((long) (timeout * 1000)))
                .strictJsonSchema(format.trim().equalsIgnoreCase("json_schema"));
        if (!thinking.equals("auto")) {
            modelBuilder.customParameters(Map.of("thinking", Map.of("type", thinking)));
        }
        OpenAiChatModel modelClient = modelBuilder.build();
        var plannerBuilder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(effectivePlannerKey)
                .modelName(plannerModel)
                .maxTokens(plannerMaxTokens)
                .timeout(Duration.ofMillis(plannerProviderTimeoutMs))
                .strictJsonSchema(false);
        if (!plannerThinking.equals("auto")) {
            plannerBuilder.customParameters(Map.of("thinking", Map.of("type", plannerThinking)));
        }
        OpenAiChatModel plannerClient = plannerBuilder.build();
        var streamingBuilder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(effectiveKey)
                .modelName(model)
                .maxTokens(maxTokens)
                .timeout(Duration.ofMillis((long) (timeout * 1000)));
        if (!thinking.equals("auto")) {
            streamingBuilder.customParameters(Map.of("thinking", Map.of("type", thinking)));
        }
        OpenAiStreamingChatModel streamingClient = streamingBuilder.build();
        MultimodalModelRoute multimodalRoute = createMultimodalRoute(
                apiKey, timeout, maxTokens, format, multimodalFallbackModel, multimodalFallbackBaseUrl,
                multimodalFallbackKeyFile, multimodalFallbackThinking);
        int multimodalAttachmentBytes = parseInt(
                "MEGURI_MULTIMODAL_MAX_ATTACHMENT_BYTES", 5 * 1024 * 1024);
        int multimodalTotalBytes = parseInt(
                "MEGURI_MULTIMODAL_MAX_TOTAL_BYTES", 10 * 1024 * 1024);
        MultimodalAttachmentResolver multimodalAttachments;
        try {
            multimodalAttachments = new MultimodalAttachmentResolver(
                    multimodalAttachmentBytes, multimodalTotalBytes);
        } catch (IllegalArgumentException error) {
            throw new LlmConfigurationException("multimodal attachment limits are invalid", error);
        }
        String prompt = readPrompt();
        metrics.registerPrompt("openai-compatible/langchain4j", model, prompt);
        String tokenizerModel = env("MEGURI_LLM_TOKENIZER_MODEL", "gpt-4o-mini").trim();
        return new LangChain4jLlmProvider(modelClient, streamingClient, mapper, prompt, format, maxConcurrency,
                releaseHeaders(), metrics, new OpenAiProviderTokenizer(tokenizerModel), promptTokenBudget,
                new AgentPlannerRoute(plannerClient, plannerMaxConcurrency,
                        Duration.ofMillis(plannerQueueTimeoutMs)), model,
                multimodalRoute, multimodalAttachments);
    }

    private static MultimodalModelRoute createMultimodalRoute(
            String primaryApiKey,
            double timeout,
            int maxTokens,
            String format,
            String fallbackModelId,
            String fallbackBaseUrl,
            String fallbackKeyFile,
            String fallbackThinking) {
        Set<String> primaryMultimodalModels = csvSet(
                env("MEGURI_LLM_MULTIMODAL_PRIMARY_MODELS", ""));
        if (fallbackModelId.isBlank()) {
            return new MultimodalModelRoute(primaryMultimodalModels, null, null, "");
        }
        validateUrl(fallbackBaseUrl);
        String fallbackApiKey = resolveFallbackApiKey(fallbackKeyFile, primaryApiKey);
        if (!isLoopback(fallbackBaseUrl) && fallbackApiKey.isBlank()) {
            throw new LlmConfigurationException(
                    "multimodal fallback requires MEGURI_LLM_MULTIMODAL_FALLBACK_API_KEY_FILE "
                            + "or MEGURI_LLM_API_KEY_FILE");
        }
        String effectiveFallbackKey = fallbackApiKey.isBlank() ? "meguri-loopback" : fallbackApiKey;
        var fallbackBuilder = OpenAiChatModel.builder()
                .baseUrl(fallbackBaseUrl)
                .apiKey(effectiveFallbackKey)
                .modelName(fallbackModelId)
                .maxTokens(maxTokens)
                .timeout(Duration.ofMillis((long) (timeout * 1000)))
                .strictJsonSchema(format.trim().equalsIgnoreCase("json_schema"));
        if (!fallbackThinking.equals("auto")) {
            fallbackBuilder.customParameters(Map.of("thinking", Map.of("type", fallbackThinking)));
        }
        var fallbackStreamingBuilder = OpenAiStreamingChatModel.builder()
                .baseUrl(fallbackBaseUrl)
                .apiKey(effectiveFallbackKey)
                .modelName(fallbackModelId)
                .maxTokens(maxTokens)
                .timeout(Duration.ofMillis((long) (timeout * 1000)));
        if (!fallbackThinking.equals("auto")) {
            fallbackStreamingBuilder.customParameters(
                    Map.of("thinking", Map.of("type", fallbackThinking)));
        }
        return new MultimodalModelRoute(primaryMultimodalModels,
                fallbackBuilder.build(), fallbackStreamingBuilder.build(), fallbackModelId);
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
            if (hasAdapter) {
                // Official hosted base models such as DeepSeek cannot emit Meguri's
                // private release headers. Only our adapter gateway owns that contract.
                headers.put("X-Meguri-Model-Id", modelId);
                headers.put("X-Meguri-Base-Revision", baseRevision);
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
    static String resolvePlannerApiKey(String plannerKeyFile, String outerApiKey) {
        if (plannerKeyFile == null || plannerKeyFile.isBlank()) {
            return outerApiKey == null ? "" : outerApiKey;
        }
        return readApiKey(plannerKeyFile, "MEGURI_AGENT_PLANNER_API_KEY_FILE");
    }

    static String resolveFallbackApiKey(String fallbackKeyFile, String outerApiKey) {
        if (fallbackKeyFile == null || fallbackKeyFile.isBlank()) {
            return outerApiKey == null ? "" : outerApiKey;
        }
        return readApiKey(fallbackKeyFile, "MEGURI_LLM_MULTIMODAL_FALLBACK_API_KEY_FILE");
    }

    private static Set<String> csvSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(item -> item.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String readApiKey(String keyFile, String settingName) {
        if (keyFile == null || keyFile.isBlank()) return "";
        try {
            Path path = Path.of(keyFile);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                throw new LlmConfigurationException(settingName + " is unreadable or must be absolute");
            }
            if (Files.size(path) > 8192) {
                throw new LlmConfigurationException(settingName + " is unexpectedly large");
            }
            String value = Files.readString(path).trim();
            if (value.isBlank()) throw new LlmConfigurationException(settingName + " must not be empty");
            return value;
        } catch (LlmConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmConfigurationException(settingName + " is unavailable", ex);
        }
    }
    private static double parseDouble(String key, double fallback) { try { return Double.parseDouble(env(key, String.valueOf(fallback))); } catch (NumberFormatException ex) { throw new LlmConfigurationException(key + " must be a number", ex); } }
    private static int parseInt(String key, int fallback) { try { return Integer.parseInt(env(key, String.valueOf(fallback))); } catch (NumberFormatException ex) { throw new LlmConfigurationException(key + " must be an integer", ex); } }
}
