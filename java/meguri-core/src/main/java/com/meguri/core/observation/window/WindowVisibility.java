package com.meguri.core.observation.window;

public enum WindowVisibility {
    VISIBLE,
    MINIMIZED,
    HIDDEN,
    CLOSED,
    UNAVAILABLE;

    public boolean observationEligible() {
        return this == VISIBLE;
    }
}
