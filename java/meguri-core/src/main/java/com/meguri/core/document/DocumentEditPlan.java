package com.meguri.core.document;

import java.util.List;
import java.util.Objects;

/** Declarative edit produced by a model; writing is deliberately a separate user action. */
public record DocumentEditPlan(
        String summary,
        String replacementText,
        List<Replacement> replacements) {
    private static final int MAX_SUMMARY_CHARS = 500;
    private static final int MAX_REPLACEMENTS = 20;
    private static final int MAX_REPLACEMENT_CHARS = 10_000;

    public DocumentEditPlan {
        summary = required(summary, "summary", MAX_SUMMARY_CHARS);
        replacementText = replacementText == null ? null : replacementText.replace("\r\n", "\n");
        replacements = replacements == null ? List.of() : List.copyOf(replacements);
        if ((replacementText == null) == replacements.isEmpty()) {
            throw new IllegalArgumentException("An edit plan must contain either replacement_text or replacements.");
        }
        if (replacements.size() > MAX_REPLACEMENTS) {
            throw new IllegalArgumentException("Too many document replacements were proposed.");
        }
    }

    public boolean isFullReplacement() {
        return replacementText != null;
    }

    public record Replacement(String find, String replace) {
        public Replacement {
            find = required(find, "find", MAX_REPLACEMENT_CHARS);
            replace = replace == null ? "" : replace;
            if (replace.length() > MAX_REPLACEMENT_CHARS) {
                throw new IllegalArgumentException("Replacement text is too long.");
            }
        }
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return value.trim();
    }
}
