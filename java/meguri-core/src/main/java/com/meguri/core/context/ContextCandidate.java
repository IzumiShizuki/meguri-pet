package com.meguri.core.context;

import java.util.List;

record ContextCandidate(
        ContextBundle.BlockType type,
        List<String> sourceIds,
        ContextBundle.Trust trust,
        String content,
        boolean required,
        int recency,
        boolean automaticRehydration) {
    ContextCandidate(
            ContextBundle.BlockType type,
            List<String> sourceIds,
            ContextBundle.Trust trust,
            String content,
            boolean required,
            int recency) {
        this(type, sourceIds, trust, content, required, recency, false);
    }

    ContextCandidate {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        content = content == null ? "" : content;
    }
}
