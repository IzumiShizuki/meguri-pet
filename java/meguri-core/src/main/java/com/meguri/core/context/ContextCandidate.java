package com.meguri.core.context;

import java.util.List;

record ContextCandidate(
        ContextBundle.BlockType type,
        List<String> sourceIds,
        ContextBundle.Trust trust,
        String content,
        boolean required,
        int recency) {
    ContextCandidate {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        content = content == null ? "" : content;
    }
}
