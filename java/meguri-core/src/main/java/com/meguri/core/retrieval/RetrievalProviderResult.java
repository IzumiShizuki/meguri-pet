package com.meguri.core.retrieval;

import java.util.LinkedHashSet;
import java.util.List;

/** Provider-local candidates plus non-fatal diagnostics preserved into the lane trace. */
public record RetrievalProviderResult(
        List<RetrievalItem> items,
        List<String> degradations) {

    public RetrievalProviderResult {
        items = items == null ? List.of() : List.copyOf(items);
        degradations = degradations == null
                ? List.of()
                : degradations.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                                List::copyOf));
    }

    public static RetrievalProviderResult success(List<RetrievalItem> items) {
        return new RetrievalProviderResult(items, List.of());
    }
}
