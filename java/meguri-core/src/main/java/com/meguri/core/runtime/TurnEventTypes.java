package com.meguri.core.runtime;

import com.meguri.core.adapter.domain.ReplayPolicy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** Java counterpart of packages/protocol/src/turn-events.ts. */
public final class TurnEventTypes {
    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "turn.started", "turn.stage.changed", "retrieval.completed", "text.delta", "text.completed", "semantic.completed",
            "expression.cue", "sprite.resolved", "memory.candidate.created",
            "memory.write.completed", "memory.updated", "relationship.updated",
            "tool.proposed", "approval.required", "approval.resolved",
            "tool.started", "tool.completed", "tool.failed",
            "skill.started", "skill.waiting", "skill.completed", "skill.failed",
            "agent.started", "agent.waiting", "agent.completed", "agent.failed",
            "semantic.cue", "voice.requested", "audio.ready", "tts.requested",
            "training.candidates.ready",
            "tts.audio.delta", "tts.completed", "session.synced", "turn.completed",
            "turn.cancelled", "turn.failed")));
    public static final Set<String> REQUIRED = Set.of(
            "turn.started", "text.completed", "turn.completed", "turn.cancelled", "turn.failed");

    private TurnEventTypes() { }

    public static boolean isSupported(String type) {
        return type != null && ALL.contains(type);
    }

    public static boolean isTerminal(String type) {
        return "turn.completed".equals(type) || "turn.cancelled".equals(type)
                || "turn.failed".equals(type);
    }

    public static boolean isRequired(String type) {
        return type != null && REQUIRED.contains(type);
    }

    public static ReplayPolicy replayPolicy(String type, Map<String, Object> data) {
        if (type == null) return ReplayPolicy.ALWAYS;
        if (type.startsWith("tts.") || "voice.requested".equals(type)
                || "audio.ready".equals(type)) {
            return ReplayPolicy.ONCE;
        }
        if ("semantic.cue".equals(type) && data != null
                && ("animation".equals(data.get("channel"))
                || "notification".equals(data.get("channel")))) {
            return ReplayPolicy.ONCE;
        }
        if (type.equals("turn.started") || type.equals("turn.stage.changed")
                || type.equals("text.delta") || type.equals("text.completed")
                || type.equals("semantic.completed")
                || type.equals("expression.cue") || type.equals("sprite.resolved")
                || type.equals("approval.required") || type.equals("memory.updated")
                || type.equals("relationship.updated") || type.equals("session.synced")
                || type.startsWith("skill.") || type.startsWith("agent.")
                || isTerminal(type)) {
            return ReplayPolicy.STATE;
        }
        return ReplayPolicy.ALWAYS;
    }
}
