package com.meguri.core.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

/** Java counterpart of packages/protocol/src/turn-events.ts. */
public final class TurnEventTypes {
    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "turn.started", "text.delta", "text.completed", "semantic.completed",
            "expression.cue", "sprite.resolved", "memory.candidate.created",
            "memory.write.completed", "tool.started", "tool.completed", "tts.requested",
            "tts.audio.delta", "tts.completed", "session.synced", "turn.completed",
            "turn.cancelled", "turn.failed")));

    private TurnEventTypes() { }

    public static boolean isSupported(String type) {
        return type != null && ALL.contains(type);
    }

    public static boolean isTerminal(String type) {
        return "turn.completed".equals(type) || "turn.cancelled".equals(type)
                || "turn.failed".equals(type);
    }
}
