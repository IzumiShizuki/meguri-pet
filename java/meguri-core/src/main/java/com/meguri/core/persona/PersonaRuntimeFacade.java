package com.meguri.core.persona;

import com.meguri.core.dto.Mode;
import com.meguri.core.persona.profile.PersonaProfileRepository;
import com.meguri.core.persona.profile.InMemoryUserProfileRepository;
import com.meguri.core.persona.profile.UserProfileRepository;
import com.meguri.core.persona.relationship.RelationshipTransitionService;
import com.meguri.core.persona.runtime.*;
import com.meguri.core.persona.scene.SceneState;
import com.meguri.core.persona.scene.SceneTransitionService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Stable integration seam for the turn runtime. All mutable state is frozen once per call. */
public final class PersonaRuntimeFacade {
    private final PersonaProfileRepository profiles; private final RelationshipTransitionService relationships;
    private final UserProfileRepository userProfiles;
    private final SceneTransitionService scenes; private final RuntimeOverrideRepository overrides;
    private final InteractionStateRepository interactions;
    private final PersonaStateReducer reducer; private final Clock clock; private final String policyRevision;
    private final Mode defaultTemporalMode;
    private final ConcurrentHashMap<String, EffectivePersonaState> stableSnapshots =
            new ConcurrentHashMap<>();
    public PersonaRuntimeFacade(PersonaProfileRepository profiles, RelationshipTransitionService relationships,
                                SceneTransitionService scenes, RuntimeOverrideRepository overrides,
                                PersonaStateReducer reducer, Clock clock, String policyRevision) {
        this.profiles = profiles; this.relationships = relationships; this.scenes = scenes; this.overrides = overrides;
        this.userProfiles = new InMemoryUserProfileRepository();
        this.interactions = null; this.reducer = reducer; this.clock = clock; this.policyRevision = policyRevision;
        this.defaultTemporalMode = Mode.WORK;
    }
    public PersonaRuntimeFacade(PersonaProfileRepository profiles, RelationshipTransitionService relationships,
                                SceneTransitionService scenes, RuntimeOverrideRepository overrides,
                                InteractionStateRepository interactions, PersonaStateReducer reducer,
                                Clock clock, String policyRevision) {
        this(profiles, relationships, scenes, overrides, interactions, reducer, clock, policyRevision, Mode.WORK);
    }
    public PersonaRuntimeFacade(PersonaProfileRepository profiles, RelationshipTransitionService relationships,
                                SceneTransitionService scenes, RuntimeOverrideRepository overrides,
                                InteractionStateRepository interactions, PersonaStateReducer reducer,
                                Clock clock, String policyRevision, Mode defaultTemporalMode) {
        this(profiles, new InMemoryUserProfileRepository(), relationships, scenes,
                overrides, interactions, reducer, clock, policyRevision,
                defaultTemporalMode);
    }

    public PersonaRuntimeFacade(PersonaProfileRepository profiles,
                                UserProfileRepository userProfiles,
                                RelationshipTransitionService relationships,
                                SceneTransitionService scenes,
                                RuntimeOverrideRepository overrides,
                                InteractionStateRepository interactions,
                                PersonaStateReducer reducer, Clock clock,
                                String policyRevision, Mode defaultTemporalMode) {
        this.profiles = profiles; this.relationships = relationships; this.scenes = scenes; this.overrides = overrides;
        this.userProfiles = userProfiles == null
                ? new InMemoryUserProfileRepository() : userProfiles;
        this.interactions = interactions; this.reducer = reducer; this.clock = clock; this.policyRevision = policyRevision;
        this.defaultTemporalMode = defaultTemporalMode == null ? Mode.WORK : defaultTemporalMode;
    }
    public EffectivePersonaState resolve(Request request) {
        var profile = profiles.findActive(request.personaId()).orElseThrow(() -> new IllegalStateException("active persona profile not found"));
        var relationship = relationships.current(request.userId());
        SceneState scene = scenes.current(request.conversationId());
        InteractionState interaction = freezeInteraction(request);
        var active = overrides.active(request.turnId(), request.sessionId(), request.client().clientType(), request.userId(), clock.instant());
        Mode temporalMode = request.temporalMode() == null ? defaultTemporalMode : request.temporalMode();
        EffectivePersonaState state = reducer.reduce(
                profile, userProfiles.findOrEmpty(request.userId()), relationship,
                scene, interaction, temporalMode, active,
                request.client(), policyRevision);
        stableSnapshots.put(snapshotKey(request), state);
        return state;
    }

    /** Uses only a previously validated snapshot or the conservative built-in baseline on authority failure. */
    public EffectivePersonaState resolveSafely(Request request) {
        try {
            return resolve(request);
        } catch (RuntimeException authorityFailure) {
            EffectivePersonaState previous = stableSnapshots.get(snapshotKey(request));
            if (previous != null) return previous;
            Mode mode = request.temporalMode() == null ? defaultTemporalMode : request.temporalMode();
            InteractionState interaction = request.interaction() == null
                    ? new InteractionState("conversation", InteractionState.Urgency.NORMAL,
                            java.util.Set.of(), null, java.util.Set.of())
                    : request.interaction();
            EffectivePersonaState baseline = reducer.reduce(
                    PersonaRuntimeDefaults.meguriProfile(),
                    PersonaRuntimeDefaults.defaultRelationship(request.userId()),
                    PersonaRuntimeDefaults.defaultScene(request.conversationId()),
                    interaction, mode, List.of(), request.client(), policyRevision);
            ArrayList<EffectivePersonaState.Resolution> trace =
                    new ArrayList<>(baseline.resolutionTrace());
            trace.add(new EffectivePersonaState.Resolution(
                    "safe-baseline", policyRevision, "authority_fallback", "applied",
                    authorityFailure.getClass().getSimpleName()));
            EffectivePersonaState safe = new EffectivePersonaState(
                    baseline.persona(), baseline.userProfile(), baseline.relationship(), baseline.scene(),
                    baseline.interaction(), baseline.mode(), baseline.envelope(),
                    baseline.capabilities(), baseline.policyRevision(),
                    baseline.resolutionTraceId() + "-safe", trace);
            stableSnapshots.put(snapshotKey(request), safe);
            return safe;
        }
    }

    private static String snapshotKey(Request request) {
        return request.personaId() + "\u0000" + request.userId()
                + "\u0000" + request.conversationId();
    }
    private InteractionState freezeInteraction(Request request) {
        if (interactions == null) return request.interaction();
        Instant now = clock.instant();
        InteractionStateSnapshot proposed = new InteractionStateSnapshot(request.turnId(), request.sessionId(),
                request.userId(), request.interaction(), 1, now);
        return interactions.freeze(proposed, new PersonaMutation("TURN_ACCEPTED", request.turnId(),
                policyRevision, request.userId(), now)).state();
    }
    public record Request(String personaId, String userId, String conversationId, String sessionId, String turnId,
                          Mode temporalMode, InteractionState interaction, ClientCapabilityState client) { }
}
