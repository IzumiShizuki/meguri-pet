package com.meguri.core.harness.persona;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.RuntimeStateMachine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Wraps the existing reducer while making its frozen state and provenance explicit. */
public final class DeterministicPersonaRuntime implements PersonaRuntime {
    private final RuntimeStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public DeterministicPersonaRuntime(RuntimeStateMachine stateMachine, ObjectMapper objectMapper) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    @Override
    public PersonaSnapshot reduce(TurnRequest request) {
        RuntimeState state = stateMachine.stateFor(request);
        Map<String, Provenance> trace = new LinkedHashMap<>();
        trace.put("client_id", new Provenance("verified_adapter_identity", "turn", 90));
        trace.put("mode", new Provenance("debounced_temporal_state", "session", 40));
        trace.put("outfit_code", new Provenance("debounced_temporal_or_runtime_override", "session", 50));
        trace.put("relationship_profile", request.relationshipProfile() == null
                ? new Provenance("relationship_or_temporal_state", "session", 60)
                : new Provenance("explicit_turn_input", "turn", 90));
        trace.put("voice_allowed", new Provenance("client_capability_policy", "turn", 80));
        trace.put("screen_context_allowed", new Provenance("client_capability_policy", "turn", 80));
        return new PersonaSnapshot(state, revision(state), trace);
    }

    private String revision(RuntimeState state) {
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(state);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(encoded), 0, 8);
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("failed to version persona snapshot", error);
        }
    }
}
