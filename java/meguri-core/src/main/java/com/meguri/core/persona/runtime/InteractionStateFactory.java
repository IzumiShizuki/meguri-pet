package com.meguri.core.persona.runtime;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class InteractionStateFactory {
    public InteractionState fromText(String text, String activeTask, Set<String> openLoops) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> style = new LinkedHashSet<>();
        if (value.contains("简短") || value.contains("直接回答") || value.contains("concise")) style.add("concise");
        if (value.contains("别撒娇") || value.contains("不要撒娇")) style.add("no_playful");
        InteractionState.Urgency urgency = value.contains("紧急") || value.contains("马上") ? InteractionState.Urgency.HIGH : InteractionState.Urgency.NORMAL;
        return new InteractionState(activeTask == null ? "conversation" : "task", urgency, style, activeTask, openLoops);
    }
}
