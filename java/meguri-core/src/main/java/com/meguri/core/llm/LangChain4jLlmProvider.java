package com.meguri.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * LangChain4j-backed provider for OpenAI-compatible gateways. No hand-written
 * HTTP is used: the injected {@link ChatModel} owns transport, retries and
 * provider-specific wire details.
 */
public final class LangChain4jLlmProvider implements LlmProvider {
    private final ChatModel model;
    private final ObjectMapper mapper;
    private final String systemPrompt;
    private final String responseFormat;
    private final Semaphore concurrency;
    private final Map<String, String> expectedReleaseHeaders;

    public LangChain4jLlmProvider(ChatModel model, ObjectMapper mapper, String systemPrompt,
                                  String responseFormat, int maxConcurrency,
                                  Map<String, String> expectedReleaseHeaders) {
        if (model == null) throw new LlmConfigurationException("LangChain4j ChatModel is required");
        if (mapper == null) throw new LlmConfigurationException("ObjectMapper is required");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new LlmConfigurationException("Meguri system prompt must not be empty");
        }
        String normalized = responseFormat == null ? "json_schema" : responseFormat.trim().toLowerCase();
        if (!normalized.equals("json_schema") && !normalized.equals("json_object")) {
            throw new LlmConfigurationException("MEGURI_LLM_RESPONSE_FORMAT must be json_schema or json_object");
        }
        if (maxConcurrency <= 0) throw new LlmConfigurationException("max concurrency must be positive");
        this.model = model;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.systemPrompt = systemPrompt.trim();
        this.responseFormat = normalized;
        this.concurrency = new Semaphore(maxConcurrency);
        this.expectedReleaseHeaders = expectedReleaseHeaders == null
                ? Map.of() : Map.copyOf(expectedReleaseHeaders);
    }

    public LangChain4jLlmProvider(ChatModel model, ObjectMapper mapper, String systemPrompt) {
        this(model, mapper, systemPrompt, "json_schema", 4, Map.of());
    }

    @Override
    public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                     List<String> canon, List<String> memories,
                                     List<String> recentContext) {
        return respond(request, state, canon, memories, recentContext, List.of());
    }

    @Override
    public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                     List<String> canon, List<String> memories,
                                     List<String> recentContext, List<String> webResults) {
        if (request == null || state == null) return Mono.error(new LlmProviderException("request and state are required"));
        return Mono.fromCallable(() -> callModel(request, state, canon, memories, recentContext, webResults))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private LlmResponse callModel(TurnRequest request, RuntimeState state,
                                  List<String> canon, List<String> memories,
                                  List<String> recentContext, List<String> webResults) {
        boolean acquired = false;
        try {
            concurrency.acquire();
            acquired = true;
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                            dev.langchain4j.data.message.UserMessage.from(contextJson(
                            request, state, canon, memories, recentContext, webResults)))
                    .responseFormat(responseFormatRequest())
                    .build();
            dev.langchain4j.model.chat.response.ChatResponse result = model.chat(chatRequest);
            if (result == null || result.aiMessage() == null || result.aiMessage().text() == null) {
                throw new LlmProviderException("LLM provider returned an empty response");
            }
            validateResponseReleaseMetadata(result);
            return parseStrict(result.aiMessage().text());
        } catch (LlmProviderException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException("LLM provider request was interrupted", ex);
        } catch (RuntimeException ex) {
            // Do not leak provider request bodies, credentials, or stack details to turn clients.
            if (isTimeout(ex)) throw new LlmProviderException("LLM provider timed out", ex);
            throw new LlmProviderException("LLM provider request failed", ex);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private ResponseFormat responseFormatRequest() {
        if (responseFormat.equals("json_object")) return ResponseFormat.JSON;
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("meguri_response").rootElement(responseSchema()).build())
                .build();
    }

    private JsonObjectSchema responseSchema() {
        JsonObjectSchema memory = JsonObjectSchema.builder()
                .addEnumProperty("type", List.of("preference", "identity", "project", "commitment", "relationship", "routine", "event"))
                .addStringProperty("summary")
                .addNumberProperty("confidence")
                .addEnumProperty("sensitivity", List.of("normal", "private", "sensitive"))
                .addEnumProperty("source_scope", List.of("current_message", "conversation"))
                .required("type", "summary", "confidence", "sensitivity", "source_scope")
                .additionalProperties(false)
                .build();
        return JsonObjectSchema.builder()
                .addStringProperty("reply")
                .addEnumProperty("expression_tag", List.of("affectionate", "angry", "confused", "embarrassed", "excited", "happy", "neutral", "sad", "sleepy", "surprised", "teasing", "worried"))
                .addEnumProperty("expression_intensity", List.of("low", "medium", "high"))
                .addEnumProperty("voice_style", List.of("neutral", "soft", "cheerful", "restrained", "sleepy", "teasing", "affectionate", "worried"))
                .addProperty("memory_candidates", JsonArraySchema.builder().items(memory).build())
                .required("reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates")
                .additionalProperties(false)
                .build();
    }

    private LlmResponse parseStrict(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node == null || !node.isObject()) throw new JsonProcessingException("response must be an object") {};
            var required = Set.of("reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates");
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(required)) {
                throw new JsonProcessingException("response fields do not match the Meguri contract") {};
            }
            return mapper.treeToValue(node, LlmResponse.class);
        } catch (Exception ex) {
            throw new LlmProviderException("LLM provider returned an invalid Meguri response", ex);
        }
    }

    private String contextJson(TurnRequest request, RuntimeState state,
                               List<String> canon, List<String> memories,
                               List<String> recentContext, List<String> webResults) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runtime_state", state);
        context.put("user_message", bounded(request.getMessage(), 8000));
        context.put("canon_examples", boundedList(canon, 3, 2000));
        context.put("long_term_memories", boundedList(memories, 5, 2000));
        context.put("recent_context", boundedList(recentContext, 20, 2000));
        context.put("web_results", boundedList(webResults, 5, 3000));
        if (responseFormat.equals("json_object")) context.put("required_output_schema", responseSchemaDocument());
        try {
            return mapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new LlmProviderException("failed to encode LLM context", ex);
        }
    }

    /**
     * DeepSeek's JSON mode needs the contract in the prompt. Keep this as a
     * plain Jackson tree rather than passing LangChain4j's internal schema
     * implementation, which is intentionally not a JSON DTO.
     */
    private Map<String, Object> responseSchemaDocument() {
        Map<String, Object> memoryProperties = new LinkedHashMap<>();
        memoryProperties.put("type", Map.of("type", "string", "enum",
                List.of("preference", "identity", "project", "commitment", "relationship", "routine", "event")));
        memoryProperties.put("summary", Map.of("type", "string"));
        memoryProperties.put("confidence", Map.of("type", "number"));
        memoryProperties.put("sensitivity", Map.of("type", "string", "enum", List.of("normal", "private", "sensitive")));
        memoryProperties.put("source_scope", Map.of("type", "string", "enum", List.of("current_message", "conversation")));
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("type", "object");
        memory.put("additionalProperties", false);
        memory.put("properties", memoryProperties);
        memory.put("required", List.of("type", "summary", "confidence", "sensitivity", "source_scope"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reply", Map.of("type", "string"));
        properties.put("expression_tag", Map.of("type", "string", "enum",
                List.of("affectionate", "angry", "confused", "embarrassed", "excited", "happy", "neutral", "sad", "sleepy", "surprised", "teasing", "worried")));
        properties.put("expression_intensity", Map.of("type", "string", "enum", List.of("low", "medium", "high")));
        properties.put("voice_style", Map.of("type", "string", "enum",
                List.of("neutral", "soft", "cheerful", "restrained", "sleepy", "teasing", "affectionate", "worried")));
        properties.put("memory_candidates", Map.of("type", "array", "items", memory));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.put("properties", properties);
        root.put("required", List.of("reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"));
        return root;
    }

    private static List<String> boundedList(List<String> values, int maxItems, int maxLength) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().limit(maxItems).map(value -> bounded(value, maxLength)).toList();
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current.getClass().getSimpleName().toLowerCase().contains("timeout")) return true;
        }
        return false;
    }

    /** Fail-closed check for gateway release metadata when an adapter supplies headers. */
    public void validateReleaseHeaders(Map<String, String> responseHeaders) {
        if (expectedReleaseHeaders.isEmpty()) return;
        for (Map.Entry<String, String> expected : expectedReleaseHeaders.entrySet()) {
            String actual = null;
            if (responseHeaders != null) {
                for (Map.Entry<String, String> candidate : responseHeaders.entrySet()) {
                    if (candidate.getKey().equalsIgnoreCase(expected.getKey())) {
                        actual = candidate.getValue();
                        break;
                    }
                }
            }
            if (!expected.getValue().equals(actual)) {
                throw new LlmProviderException("LLM gateway release metadata does not match the configured release");
            }
        }
    }

    private void validateResponseReleaseMetadata(dev.langchain4j.model.chat.response.ChatResponse response) {
        if (expectedReleaseHeaders.isEmpty()) return;
        if (!(response.metadata() instanceof OpenAiChatResponseMetadata metadata)
                || metadata.rawHttpResponse() == null) {
            throw new LlmProviderException("LLM gateway release metadata does not match the configured release");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        metadata.rawHttpResponse().headers().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) headers.put(key, values.getFirst());
        });
        validateReleaseHeaders(headers);
    }

    @Override
    public String providerName() {
        return "openai-compatible/langchain4j";
    }
}
