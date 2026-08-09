package com.meguri.core.react;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persists only a bounded summary and digest, never the full raw capability result. */
public final class DefaultObservationNormalizer implements ObservationNormalizer {
    private final int maximumSummaryCharacters;

    public DefaultObservationNormalizer(int maximumSummaryCharacters) {
        if (maximumSummaryCharacters < 1) {
            throw new IllegalArgumentException("maximumSummaryCharacters must be positive");
        }
        this.maximumSummaryCharacters = maximumSummaryCharacters;
    }

    @Override
    public NormalizedReactObservation normalize(RawReactObservation observation) {
        Objects.requireNonNull(observation, "observation");
        String summary = sanitize(observation.summary());
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("status", observation.status());
        digestInput.put("data", observation.data());
        digestInput.put("summary", summary);
        digestInput.put("error_code", observation.errorCode());
        digestInput.put("trust_label", observation.trustLabel());
        return new NormalizedReactObservation(
                observation.status(),
                summary,
                ActionDigest.sha256(digestInput),
                observation.errorCode(),
                observation.trustLabel(),
                observation.retryable(),
                observation.evidenceSufficient(),
                observation.tokensUsed(),
                observation.costUnits(),
                false);
    }

    private String sanitize(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximumSummaryCharacters
                ? normalized : normalized.substring(0, maximumSummaryCharacters);
    }
}
