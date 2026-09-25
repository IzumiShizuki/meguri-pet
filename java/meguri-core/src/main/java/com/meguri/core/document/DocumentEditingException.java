package com.meguri.core.document;

/** A safe, user-visible failure while reading or editing an approved document. */
public final class DocumentEditingException extends RuntimeException {
    public DocumentEditingException(String message) {
        super(message);
    }

    public DocumentEditingException(String message, Throwable cause) {
        super(message, cause);
    }
}
