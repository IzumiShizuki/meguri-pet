package com.meguri.core.harness;

import com.meguri.core.dto.TurnRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Commands accepted by the public Turn Runtime seam. */
public sealed interface TurnCommand permits TurnCommand.Start, TurnCommand.Cancel {
    Duration DEFAULT_DEADLINE = Duration.ofSeconds(90);

    record Start(TurnRequest request, String idempotencyKey, Instant deadlineAt) implements TurnCommand {
        public Start {
            Objects.requireNonNull(request, "request");
            idempotencyKey = normalize(idempotencyKey);
            deadlineAt = deadlineAt == null ? Instant.now().plus(DEFAULT_DEADLINE) : deadlineAt;
            if (!deadlineAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("deadline_at must be in the future");
            }
        }

        public Start(TurnRequest request, String idempotencyKey) {
            this(request, idempotencyKey, null);
        }
    }

    record Cancel(String turnId, String reason) implements TurnCommand {
        public Cancel {
            turnId = required(turnId, "turn_id");
            reason = normalize(reason);
            if (reason == null) reason = "client_request";
        }
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
