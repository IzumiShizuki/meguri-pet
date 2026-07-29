package com.meguri.core.persona.runtime;

import com.meguri.core.dto.Mode;
import com.meguri.core.persona.profile.PersonaProfile;
import com.meguri.core.persona.profile.UserProfile;
import com.meguri.core.persona.relationship.RelationshipState;
import com.meguri.core.persona.scene.SceneState;
import java.util.List;

public record EffectivePersonaState(PersonaProfile persona, UserProfile userProfile,
                                    RelationshipState relationship, SceneState scene,
                                    InteractionState interaction, Mode mode, AllowedBehaviorEnvelope envelope,
                                    ClientCapabilityState capabilities, String policyRevision,
                                    String resolutionTraceId, List<Resolution> resolutionTrace) {
    public EffectivePersonaState {
        userProfile = userProfile == null
                ? UserProfile.empty(relationship.userId()) : userProfile;
        resolutionTrace = resolutionTrace == null ? List.of() : List.copyOf(resolutionTrace);
    }

    /** Backward-compatible constructor for callers that predate typed UserProfile input. */
    public EffectivePersonaState(PersonaProfile persona, RelationshipState relationship,
                                 SceneState scene, InteractionState interaction, Mode mode,
                                 AllowedBehaviorEnvelope envelope,
                                 ClientCapabilityState capabilities, String policyRevision,
                                 String resolutionTraceId, List<Resolution> resolutionTrace) {
        this(persona, UserProfile.empty(relationship.userId()), relationship, scene,
                interaction, mode, envelope, capabilities, policyRevision,
                resolutionTraceId, resolutionTrace);
    }
    public record Resolution(String source, String sourceVersion, String field, String value, String reason) {
        public Resolution(String source, String field, String value) {
            this(source, "unknown", field, value, "legacy resolution");
        }
    }
}
