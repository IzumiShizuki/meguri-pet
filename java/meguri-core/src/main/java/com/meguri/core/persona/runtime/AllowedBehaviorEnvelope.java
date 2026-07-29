package com.meguri.core.persona.runtime;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;

public record AllowedBehaviorEnvelope(Set<String> allowedBehaviors, Set<String> blockedBehaviors,
                                      Set<String> expressionTags, Set<String> voiceStyles) {
    public AllowedBehaviorEnvelope {
        allowedBehaviors = immutable(allowedBehaviors); blockedBehaviors = immutable(blockedBehaviors);
        expressionTags = immutable(expressionTags); voiceStyles = immutable(voiceStyles);
    }
    private static Set<String> immutable(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }
}
