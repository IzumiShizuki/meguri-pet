package com.meguri.core.resource;

import com.meguri.core.resources.LocalResourceCandidate;
import com.meguri.core.resources.ResourceSearchGateway;
import com.meguri.core.resources.ResourceSearchResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything-backed resource picker with a second, authoritative path gate.
 * It only returns metadata and never reads, opens or executes a result.
 */
public final class EverythingResourceSearchGateway implements ResourceSearchGateway {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int HARD_RESULT_LIMIT = 20;
    private static final int SEARCH_MULTIPLIER = 10;

    private final EsEverythingSearchGateway client;

    public EverythingResourceSearchGateway(EsEverythingSearchGateway client) {
        this.client = client;
    }

    @Override
    public Mono<ResourceSearchResponse> search(String rawQuery, int requestedLimit) {
        return Mono.fromCallable(() -> searchBlocking(rawQuery, requestedLimit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    ResourceSearchResponse searchBlocking(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.startsWith("@")) query = query.substring(1).strip();
        if (query.isBlank()) return ResourceSearchResponse.unavailable("", "Resource query must not be blank.");
        if (query.length() > MAX_QUERY_LENGTH) {
            return ResourceSearchResponse.unavailable(query.substring(0, MAX_QUERY_LENGTH),
                    "Resource query must not exceed 200 characters.");
        }
        int limit = Math.clamp(requestedLimit, 1, HARD_RESULT_LIMIT);
        if (!client.available()) {
            return ResourceSearchResponse.unavailable(query,
                    "Everything is running, but its official ES command-line client is not installed.");
        }

        List<EverythingSearchHit> hits = client.search(query, Math.min(500, limit * SEARCH_MULTIPLIER));
        List<LocalResourceCandidate> candidates = new ArrayList<>(limit);
        for (EverythingSearchHit hit : hits) {
            safeCandidate(hit).ifPresent(candidate -> {
                if (candidates.size() < limit) candidates.add(candidate);
            });
            if (candidates.size() >= limit) break;
        }
        String message = candidates.isEmpty()
                ? (hits.isEmpty() ? "No local resource matched the query."
                : "Matching local resources were found but could not be safely offered.")
                : "";
        return new ResourceSearchResponse(query, true, candidates, message);
    }

    private java.util.Optional<LocalResourceCandidate> safeCandidate(EverythingSearchHit hit) {
        if (hit == null || hit.path() == null || hit.path().isBlank()) return java.util.Optional.empty();
        return LocalResourcePathPolicy.resolve(hit.path(), true).map(resolved -> {
            String name = resolved.path().getFileName() == null
                    ? resolved.path().toString() : resolved.path().getFileName().toString();
            Long size = resolved.attributes().isDirectory() ? null : resolved.attributes().size();
            Instant modified = resolved.attributes().lastModifiedTime().toInstant();
            return java.util.Optional.of(new LocalResourceCandidate(
                    LocalResourcePathPolicy.stableId(resolved.path()), name, resolved.path().toString(),
                    kind(name, resolved.attributes().isDirectory()),
                    size, modified.toString()));
        }).orElseGet(java.util.Optional::empty);
    }

    private static String kind(String name, boolean directory) {
        if (directory) return "directory";
        return "file";
    }

}
