package com.meguri.core.persona.presentation;

import com.meguri.core.persona.runtime.EffectivePersonaState;

/** Deterministically filters semantic intent; it never accepts paths, scripts or commands. */
public final class PresentationResolver {
    public ResolvedPresentation resolve(PresentationIntent intent, EffectivePersonaState state) {
        String expression = state.capabilities().expression() && state.envelope().expressionTags().contains(intent.expressionTag())
                ? intent.expressionTag() : "neutral";
        String voice = state.capabilities().voice() && state.envelope().voiceStyles().contains(intent.voiceStyle())
                ? intent.voiceStyle() : null;
        String gesture = state.capabilities().gesture() && !"none".equals(intent.gestureTag()) ? intent.gestureTag() : null;
        return new ResolvedPresentation(expression, voice, gesture, intent.intensity());
    }
    public record ResolvedPresentation(String expressionTag, String voiceStyle, String gestureTag, double intensity) { }
}
