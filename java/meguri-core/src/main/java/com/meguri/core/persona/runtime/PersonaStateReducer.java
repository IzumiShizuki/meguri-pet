package com.meguri.core.persona.runtime;

import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.profile.PersonaProfile;
import com.meguri.core.persona.profile.UserProfile;
import com.meguri.core.persona.relationship.RelationshipState;
import com.meguri.core.persona.scene.SceneState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Three stages: constitution/relationship envelope, turn modifiers, client filtering. */
public final class PersonaStateReducer {
    public EffectivePersonaState reduce(PersonaProfile profile, RelationshipState relationship, SceneState scene,
                                        InteractionState interaction, Mode temporalMode, List<PersonaOverride> overrides,
                                        ClientCapabilityState client, String policyRevision) {
        return reduce(profile, UserProfile.empty(relationship.userId()), relationship,
                scene, interaction, temporalMode, overrides, client, policyRevision);
    }

    public EffectivePersonaState reduce(PersonaProfile profile, UserProfile userProfile,
                                        RelationshipState relationship, SceneState scene,
                                        InteractionState interaction, Mode temporalMode,
                                        List<PersonaOverride> overrides,
                                        ClientCapabilityState client, String policyRevision) {
        Set<String> allowed = new LinkedHashSet<>(List.of("answer", "care", "clarify"));
        Set<String> blocked = new LinkedHashSet<>(profile.hardBoundaries());
        if (relationship.stage() != Relationship.LOVER) blocked.addAll(Set.of("romantic_confession", "intimate_touch"));
        Set<String> expressions = new LinkedHashSet<>(List.of("neutral", "happy", "worried", "sad"));
        Set<String> voices = new LinkedHashSet<>(List.of("neutral", "gentle"));
        List<EffectivePersonaState.Resolution> trace = new ArrayList<>();
        Mode mode = temporalMode;
        trace.add(resolution("constitution", profile.revision(), "persona", profile.personaId(), "active immutable profile"));
        trace.add(resolution("relationship", Long.toString(relationship.version()), "relationship",
                relationship.stage().value(), "server-authoritative user state"));
        addUserProfileProvenance(trace, userProfile);
        trace.add(resolution("temporal", policyRevision, "mode", mode.value(), "turn-start temporal snapshot"));
        trace.add(resolution("constitution", profile.revision(), "blocked_behaviors",
                blocked.toString(), "hard boundaries and relationship envelope"));
        if (scene != null && scene.phase() == SceneState.Phase.ACTIVE && scene.type() == SceneState.Type.COMFORT) {
            allowed.add("comfort"); trace.add(resolution("scene", Long.toString(scene.version()),
                    "warmth", "high", "active comfort scene"));
        }
        for (PersonaOverride override : overrides) {
            if (override.type() == PersonaOverride.Type.MODE && override.value().containsKey("mode")) {
                mode = Mode.fromValue(override.value().get("mode"));
                trace.add(resolution("override:" + override.source(), Long.toString(override.version()),
                        "mode", mode.value(), override.scope() + " override " + override.id()));
            }
            if (override.type() == PersonaOverride.Type.BOUNDARY) override.value().values().stream().sorted().forEach(blocked::add);
        }
        if (interaction.styleConstraints().contains("concise")) trace.add(resolution("interaction", "turn-frozen",
                "reply_length", "concise", "explicit interaction constraint"));
        if (!client.expression()) expressions.clear();
        if (!client.voice()) voices.clear();
        trace.add(resolution("client", client.clientType(), "presentation_capabilities",
                "voice=" + client.voice() + ",expression=" + client.expression() + ",gesture=" + client.gesture(),
                "client capability degradation"));
        AllowedBehaviorEnvelope envelope = new AllowedBehaviorEnvelope(allowed, blocked, expressions, voices);
        trace.add(resolution("reducer", policyRevision, "allowed_behavior_envelope", envelope.toString(),
                "constitution -> turn modifiers -> client filter"));
        String traceId = digest(profile.revision() + userProfile.revision()
                + relationship.version() + String.valueOf(scene) + interaction
                + mode + envelope + policyRevision);
        return new EffectivePersonaState(profile, userProfile, relationship, scene,
                interaction, mode, envelope, client, policyRevision, traceId, trace);
    }

    private static void addUserProfileProvenance(
            List<EffectivePersonaState.Resolution> trace, UserProfile profile) {
        if (profile.isEmpty()) {
            trace.add(resolution("user-profile", profile.revision(), "user_profile",
                    "empty", "no user profile is stored; typed empty profile applied"));
            return;
        }
        if (!profile.displayName().isBlank()) {
            trace.add(resolution("user-profile", profile.revision(),
                    "user_profile.display_name", profile.displayName(),
                    "user-owned profile field"));
        }
        if (!profile.locale().isBlank()) {
            trace.add(resolution("user-profile", profile.revision(),
                    "user_profile.locale", profile.locale(),
                    "user-owned profile field"));
        }
        if (!profile.communicationPreferences().isEmpty()) {
            trace.add(resolution("user-profile", profile.revision(),
                    "user_profile.communication_preferences",
                    profile.communicationPreferences().toString(),
                    "user-owned profile field"));
        }
    }
    private static EffectivePersonaState.Resolution resolution(String source, String version, String field,
                                                                String value, String reason) {
        return new EffectivePersonaState.Resolution(source, version, field, value, reason);
    }
    private static String digest(String input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)), 0, 12); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
