package com.meguri.core.persona.scene;

import com.meguri.core.persona.PersonaMutation;
import java.time.Clock;
import java.time.Duration;

/** Conversation-scoped scene lifecycle with evidence, hysteresis, TTL and cooldown. */
public final class SceneTransitionService {
    private final SceneStateRepository repository;
    private final Clock clock; private final Duration ttl; private final Duration cooldown;
    public SceneTransitionService(Clock clock, Duration ttl, Duration cooldown) { this(new InMemorySceneStateRepository(), clock, ttl, cooldown); }
    public SceneTransitionService(SceneStateRepository repository, Clock clock, Duration ttl, Duration cooldown) {
        this.repository = repository; this.clock = clock; this.ttl = ttl; this.cooldown = cooldown;
    }

    public SceneState signal(String conversationId, SceneState.Type type, double confidence, boolean explicit) {
        var now = clock.instant();
        SceneState current = current(conversationId);
        if (current != null && current.phase() == SceneState.Phase.CLOSED && now.isBefore(current.cooldownUntil()) && !explicit) return current;
        if (current == null || current.phase() == SceneState.Phase.CLOSED || current.type() != type) {
            int evidence = explicit ? 2 : 1;
            SceneState.Phase phase = explicit || confidence >= .85 ? SceneState.Phase.ACTIVE : SceneState.Phase.CANDIDATE;
            long expected = current == null ? 0 : current.version();
            SceneState next = new SceneState(conversationId, type, phase, confidence, evidence, now,
                    now.plus(ttl), now, now, expected + 1);
            return save(current, next, expected, explicit ? "explicit_signal" : "detected_signal");
        }
        if (current.phase() == SceneState.Phase.CANDIDATE) {
            int evidence = current.evidenceCount() + 1;
            SceneState.Phase phase = explicit || evidence >= 2 || confidence >= .85 ? SceneState.Phase.ACTIVE : current.phase();
            SceneState next = new SceneState(conversationId, type, phase, Math.max(current.confidence(), confidence), evidence,
                    current.startedAt(), now.plus(ttl), now, current.cooldownUntil(), current.version() + 1);
            return save(current, next, current.version(), explicit ? "explicit_signal" : "detected_signal");
        }
        return current;
    }

    public SceneState weaken(String conversationId, double confidence) {
        var now = clock.instant(); SceneState current = current(conversationId); if (current == null) return null;
        SceneState.Phase phase = current.phase();
        if (phase == SceneState.Phase.ACTIVE && confidence < .35) phase = SceneState.Phase.ENDING;
        else if (phase == SceneState.Phase.ENDING && confidence < .20) phase = SceneState.Phase.CLOSED;
        SceneState next = new SceneState(conversationId, current.type(), phase, confidence, current.evidenceCount(),
                current.startedAt(), current.expiresAt(), now,
                phase == SceneState.Phase.CLOSED ? now.plus(cooldown) : current.cooldownUntil(), current.version() + 1);
        return save(current, next, current.version(), "confidence_weakened");
    }

    public SceneState current(String conversationId) {
        SceneState state = repository.findScene(conversationId).orElse(null); if (state == null) return null;
        if ((state.phase() == SceneState.Phase.ACTIVE || state.phase() == SceneState.Phase.CANDIDATE) && !state.expiresAt().isAfter(clock.instant())) {
            SceneState expired = new SceneState(conversationId, state.type(), SceneState.Phase.ENDING, state.confidence(), state.evidenceCount(),
                    state.startedAt(), state.expiresAt(), clock.instant(), state.cooldownUntil(), state.version() + 1);
            return save(state, expired, state.version(), "ttl_expired");
        }
        return state;
    }

    private SceneState save(SceneState previous, SceneState next, long expectedVersion, String trigger) {
        return repository.save(previous, next, expectedVersion,
                new PersonaMutation("SCENE", trigger, "scene-policy-v1", "persona-runtime", clock.instant()));
    }
}
