package com.meguri.core.persona.runtime;

import java.util.Set;

public record InteractionState(String intent, Urgency urgency, Set<String> styleConstraints,
                               String activeTask, Set<String> openLoopIds) {
    public InteractionState {
        intent = intent == null ? "conversation" : intent;
        urgency = urgency == null ? Urgency.NORMAL : urgency;
        styleConstraints = styleConstraints == null ? Set.of() : Set.copyOf(styleConstraints);
        openLoopIds = openLoopIds == null ? Set.of() : Set.copyOf(openLoopIds);
    }
    public enum Urgency { LOW, NORMAL, HIGH }
}
