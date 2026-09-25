package com.meguri.core.resources;

import java.util.List;

/** Result returned by the optional local, read-only resource index. */
public record ResourceSearchResponse(
        String query,
        boolean available,
        List<LocalResourceCandidate> candidates,
        String message) {

    public ResourceSearchResponse {
        query = query == null ? "" : query;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        message = message == null ? "" : message;
    }

    public static ResourceSearchResponse unavailable(String query, String message) {
        return new ResourceSearchResponse(query, false, List.of(), message);
    }
}
