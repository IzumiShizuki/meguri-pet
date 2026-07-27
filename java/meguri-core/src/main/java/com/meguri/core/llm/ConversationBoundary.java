package com.meguri.core.llm;

/** Bounded semantic decision used only after a desktop idle-gap candidate window. */
public record ConversationBoundary(boolean sameContext, double confidence) {
    public ConversationBoundary {
        confidence = Math.max(0d, Math.min(1d, confidence));
    }
}
