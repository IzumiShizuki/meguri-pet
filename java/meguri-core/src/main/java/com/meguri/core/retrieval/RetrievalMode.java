package com.meguri.core.retrieval;

/** Server-authoritative retrieval depth. */
public enum RetrievalMode {
    NONE,
    FAST,
    SLOW;

    public boolean permitsLocal() {
        return this != NONE;
    }

    public boolean permitsWeb() {
        return this == SLOW;
    }
}
