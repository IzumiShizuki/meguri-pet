package com.meguri.core.runtime;

/** The same scoped idempotency key was reused with a different request body. */
public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("idempotency key is already bound to a different turn payload");
    }
}
