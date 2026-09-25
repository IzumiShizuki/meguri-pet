package com.meguri.core.document;

import dev.langchain4j.data.message.TextContent;
import java.nio.file.Path;
import java.util.Objects;

/** A bounded, revalidated document explicitly selected for a single turn. */
public record ApprovedDocument(
        String referenceId,
        String name,
        Path path,
        Kind kind,
        String sha256,
        String text,
        long byteSize) {

    public ApprovedDocument {
        referenceId = required(referenceId, "referenceId");
        name = required(name, "name");
        path = Objects.requireNonNull(path, "path");
        kind = Objects.requireNonNull(kind, "kind");
        sha256 = required(sha256, "sha256");
        text = Objects.requireNonNull(text, "text");
        if (byteSize < 0) throw new IllegalArgumentException("byteSize must not be negative");
    }

    /** Gives the model content, but not a path or general filesystem authority. */
    public TextContent modelContent() {
        return TextContent.from("""
                [APPROVED_DOCUMENT]
                reference_id: %s
                name: %s
                kind: %s
                sha256: %s
                The following is the complete bounded content of the one document selected by the user.
                You have no access to any other local file. To propose a change, explain it normally and add one fenced
                `meguri-document-edit` JSON object using this reference_id and sha256. Never claim you already wrote it.
                [/APPROVED_DOCUMENT]

                %s
                """.formatted(referenceId, name, kind.wireName, sha256, text));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum Kind {
        TEXT("text"),
        DOCX("docx");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
