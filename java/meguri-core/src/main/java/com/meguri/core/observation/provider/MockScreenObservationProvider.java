package com.meguri.core.observation.provider;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Deterministic provider for tests and contract development; it never captures a screen. */
public final class MockScreenObservationProvider implements ScreenObservationProvider {
    private final Deque<ObservationProviderResult> responses = new ArrayDeque<>();
    private int invocationCount;

    public MockScreenObservationProvider enqueue(ObservationProviderResult response) {
        responses.addLast(Objects.requireNonNull(response, "response must not be null"));
        return this;
    }

    @Override
    public ObservationProviderResult observe(ObservationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        invocationCount++;
        ObservationProviderResult response = responses.pollFirst();
        return response == null
                ? ObservationProviderResult.unavailable("mock response not configured")
                : response;
    }

    public int invocationCount() {
        return invocationCount;
    }
}
