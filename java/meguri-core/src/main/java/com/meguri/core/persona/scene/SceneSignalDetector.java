package com.meguri.core.persona.scene;

import java.util.Locale;
import java.util.Optional;

/** Conservative deterministic detector; model signals remain candidates at the service boundary. */
public final class SceneSignalDetector {
    public Optional<Signal> detect(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (contains(value, "难过", "伤心", "害怕", "陪陪我", "sad", "upset")) return Optional.of(new Signal(SceneState.Type.COMFORT, .9, true));
        if (contains(value, "庆祝", "成功了", "通过了", "celebrate")) return Optional.of(new Signal(SceneState.Type.CELEBRATION, .85, true));
        if (contains(value, "学习", "复习", "study")) return Optional.of(new Signal(SceneState.Type.STUDY, .65, false));
        return Optional.empty();
    }
    private static boolean contains(String value, String... terms) { for (String term : terms) if (value.contains(term)) return true; return false; }
    public record Signal(SceneState.Type type, double confidence, boolean explicit) { }
}
