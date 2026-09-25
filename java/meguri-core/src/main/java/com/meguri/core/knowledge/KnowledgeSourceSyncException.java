package com.meguri.core.knowledge;

public final class KnowledgeSourceSyncException extends RuntimeException {
    public KnowledgeSourceSyncException(String message) {
        super(message);
    }

    public KnowledgeSourceSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
