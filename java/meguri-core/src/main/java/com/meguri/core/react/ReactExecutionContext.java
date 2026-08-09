package com.meguri.core.react;

import com.meguri.core.agent.CancellationToken;

import java.time.Instant;

public record ReactExecutionContext(
        ReactInvocationScope scope,
        int roundIndex,
        String actionDigest,
        Instant deadlineAt,
        long remainingTokens,
        long remainingCostUnits,
        CancellationToken cancellation) {
}
