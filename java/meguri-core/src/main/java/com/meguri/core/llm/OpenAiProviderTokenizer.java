package com.meguri.core.llm;

import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import java.util.List;

/** JTokkit-backed tokenizer used by OpenAI-compatible provider profiles. */
public final class OpenAiProviderTokenizer implements ProviderTokenizer {
    private final String modelName;
    private final OpenAiTokenCountEstimator estimator;

    public OpenAiProviderTokenizer(String modelName) {
        this.modelName = modelName == null || modelName.isBlank() ? "gpt-4o-mini" : modelName.trim();
        try {
            this.estimator = new OpenAiTokenCountEstimator(this.modelName);
        } catch (RuntimeException unsupported) {
            throw new LlmConfigurationException(
                    "no tokenizer mapping is available for MEGURI_LLM_MODEL=" + this.modelName,
                    unsupported);
        }
    }

    @Override
    public int count(String text) {
        return estimator.estimateTokenCountInText(text == null ? "" : text);
    }

    @Override
    public String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        List<Integer> encoded = estimator.encode(text);
        if (encoded.size() <= maxTokens) return text;
        return estimator.decode(encoded.subList(0, maxTokens));
    }

    @Override
    public String name() {
        return "openai-compatible:" + modelName;
    }
}
