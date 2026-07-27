package com.meguri.core.runtime;

import java.util.List;

/** Durable projection seam for message DAGs, references, and summaries. */
public interface SessionContextPersistence {
    List<SessionContextStore.GraphSnapshot> loadAll();

    void save(SessionContextStore.GraphSnapshot snapshot);

    void clear();
}
