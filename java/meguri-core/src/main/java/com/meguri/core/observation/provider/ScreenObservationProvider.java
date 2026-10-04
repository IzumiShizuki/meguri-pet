package com.meguri.core.observation.provider;

@FunctionalInterface
public interface ScreenObservationProvider {
    ObservationProviderResult observe(ObservationRequest request);
}
