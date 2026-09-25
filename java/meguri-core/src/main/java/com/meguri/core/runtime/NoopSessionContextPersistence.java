package com.meguri.core.runtime;

import java.util.List;

/** Offline default that leaves the context graph process-local. */
public final class NoopSessionContextPersistence implements SessionContextPersistence {
    @Override
    public List<SessionContextStore.GraphSnapshot> loadAll() {
        return List.of();
    }

    @Override
    public void save(SessionContextStore.GraphSnapshot snapshot) { }

    @Override
    public void clear() { }
}
