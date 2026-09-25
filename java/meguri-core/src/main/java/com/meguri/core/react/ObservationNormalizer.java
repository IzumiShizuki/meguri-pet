package com.meguri.core.react;

@FunctionalInterface
public interface ObservationNormalizer {
    NormalizedReactObservation normalize(RawReactObservation observation);
}
