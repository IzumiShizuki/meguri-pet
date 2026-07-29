package com.meguri.core.llm;

import java.util.ArrayList;
import java.util.List;

/** Deterministic tokenizer for the offline mock provider. */
public final class DeterministicProviderTokenizer implements ProviderTokenizer {
    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.codePointCount(0, text.length()) + 2) / 3);
    }

    @Override
    public String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        int maximumCodePoints = Math.multiplyExact(maxTokens, 3);
        int[] codePoints = text.codePoints().limit(maximumCodePoints).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    @Override
    public String name() {
        return "meguri-mock-v1";
    }
}
