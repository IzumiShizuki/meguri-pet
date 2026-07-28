package com.meguri.core.knowledge.notion;

import org.springframework.http.HttpStatus;

final class NotionSyncAccessDeniedException extends RuntimeException {
    private final HttpStatus status;

    NotionSyncAccessDeniedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
