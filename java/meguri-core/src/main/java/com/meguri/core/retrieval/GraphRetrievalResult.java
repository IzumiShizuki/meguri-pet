package com.meguri.core.retrieval;

import java.util.List;

public record GraphRetrievalResult(
        Status status,
        List<RetrievalItem> items,
        List<String> degradations) {
    public GraphRetrievalResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        items = items == null ? List.of() : List.copyOf(items);
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
    }

    public boolean fallbackRequired() {
        return status == Status.FALLBACK;
    }

    public enum Status {
        SUCCESS,
        DISABLED,
        FALLBACK
    }
}
