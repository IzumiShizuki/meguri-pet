package com.meguri.core.rag;

import com.meguri.core.dto.RuntimeState;
import java.util.List;
import reactor.core.publisher.Flux;

/** Canonical retrieval boundary. The authoritative memory store is separate. */
@FunctionalInterface
public interface RagProvider {
    List<String> search(String query, RuntimeState state, int limit);

    default SearchResult searchWithDiagnostics(String query, RuntimeState state, int limit) {
        return new SearchResult(search(query, state, limit), List.of());
    }

    record SearchResult(List<String> items, List<String> degradations) {
        public SearchResult {
            items = items == null ? List.of() : List.copyOf(items);
            degradations = degradations == null ? List.of() : List.copyOf(degradations);
        }
    }

    default List<String> search(String query, RuntimeState state) {
        return search(query, state, 3);
    }

    default Flux<String> searchAsync(String query, RuntimeState state, int limit) {
        return Flux.fromIterable(search(query, state, limit));
    }
}
