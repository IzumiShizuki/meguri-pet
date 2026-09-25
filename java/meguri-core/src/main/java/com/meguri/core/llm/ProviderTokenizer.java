package com.meguri.core.llm;

/** Provider-specific token counting and deterministic truncation. */
public interface ProviderTokenizer {
    int count(String text);

    String truncate(String text, int maxTokens);

    String name();
}
