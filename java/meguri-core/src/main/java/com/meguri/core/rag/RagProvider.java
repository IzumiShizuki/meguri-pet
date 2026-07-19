package com.meguri.core.rag;

import com.meguri.core.dto.RuntimeState;
import java.util.List;
import reactor.core.publisher.Flux;

/** Canonical retrieval boundary. The authoritative memory store is separate. */
@FunctionalInterface
public interface RagProvider {
    List<String> search(String query, RuntimeState state, int limit);

    default List<String> search(String query, RuntimeState state) {
        return search(query, state, 3);
    }

    default Flux<String> searchAsync(String query, RuntimeState state, int limit) {
        return Flux.fromIterable(search(query, state, limit));
    }
}
