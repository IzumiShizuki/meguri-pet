package com.meguri.core.runtime;

import java.util.List;
import java.util.Optional;

/** Durable projection seam for message DAGs, references, and summaries. */
public interface SessionContextPersistence {
    List<SessionContextStore.GraphSnapshot> loadAll();

    default Optional<SessionContextStore.GraphSnapshot> load(
            String userId, String clientId, String sessionId) {
        return loadAll().stream()
                .filter(snapshot -> snapshot.userId().equals(userId)
                        && snapshot.clientId().equals(clientId)
                        && snapshot.sessionId().equals(sessionId))
                .findFirst();
    }

    void save(SessionContextStore.GraphSnapshot snapshot);

    void clear();
}
