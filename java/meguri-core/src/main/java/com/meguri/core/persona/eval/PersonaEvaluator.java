package com.meguri.core.persona.eval;

import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.runtime.EffectivePersonaState;
import com.meguri.core.persona.scene.SceneState;

import java.util.*;
import java.util.Locale;

/** Deterministic seven-dimension acceptance evaluator required by Persona Runtime 20.4. */
public final class PersonaEvaluator {
    public Report evaluate(Case value, Observation observation) {
        Objects.requireNonNull(value, "value"); Objects.requireNonNull(observation, "observation");
        Map<Dimension, Check> checks = new EnumMap<>(Dimension.class);
        EffectivePersonaState state = observation.state();
        checks.put(Dimension.IDENTITY_CONSISTENCY, check(state.persona().revision().equals(value.personaRevision()),
                "expected persona revision " + value.personaRevision()));
        checks.put(Dimension.RELATIONSHIP_BOUNDARY, check(state.relationship().stage().ordinal() <= value.maximumRelationship().ordinal()
                        && !containsAny(observation.response(), value.forbiddenRelationshipMarkers()),
                "relationship stage or output crossed the configured boundary"));
        checks.put(Dimension.MODE_CONSISTENCY, check(state.mode() == value.expectedMode(),
                "expected mode " + value.expectedMode()));
        checks.put(Dimension.SCENE_APPROPRIATENESS, check(value.expectedScene() == null
                        || state.scene() != null && state.scene().phase() == SceneState.Phase.ACTIVE
                        && state.scene().type() == value.expectedScene()
                        && state.envelope().allowedBehaviors().contains(value.requiredSceneBehavior()),
                "scene is not active or required behavior is absent"));
        checks.put(Dimension.MEMORY_NATURALNESS, check(containsAll(observation.response(), value.memoryFactMarkers())
                        && !observation.response().contains("<untrusted-data>")
                        && observation.blocks().stream().filter(b -> b.source() == PromptPolicyComposer.Source.MEMORY)
                        .allMatch(b -> b.role() == PromptPolicyComposer.Role.USER_DATA),
                "memory was omitted, exposed as markup, or promoted to policy"));
        checks.put(Dimension.PROMISE_CONSISTENCY, check(containsAll(observation.response(), value.commitmentMarkers()),
                "an active commitment was not preserved"));
        checks.put(Dimension.CONTRADICTION_REPAIR, check(containsAll(observation.response(), value.repairMarkers()),
                "contradiction repair markers are missing"));
        return new Report(checks);
    }

    private static Check check(boolean passed, String detail) { return new Check(passed, detail); }
    private static boolean containsAll(String text, Collection<String> markers) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return markers.stream().map(value -> value.toLowerCase(Locale.ROOT)).allMatch(normalized::contains);
    }
    private static boolean containsAny(String text, Collection<String> markers) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return markers.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }

    public enum Dimension { IDENTITY_CONSISTENCY, RELATIONSHIP_BOUNDARY, MODE_CONSISTENCY,
        SCENE_APPROPRIATENESS, MEMORY_NATURALNESS, PROMISE_CONSISTENCY, CONTRADICTION_REPAIR }
    public record Check(boolean passed, String detail) { }
    public record Report(Map<Dimension, Check> checks) {
        public Report { checks = Collections.unmodifiableMap(new EnumMap<>(checks)); }
        public boolean passed() { return checks.size() == Dimension.values().length && checks.values().stream().allMatch(Check::passed); }
        public Set<Dimension> failures() {
            EnumSet<Dimension> result = EnumSet.noneOf(Dimension.class);
            checks.forEach((dimension, check) -> { if (!check.passed()) result.add(dimension); });
            return Collections.unmodifiableSet(result);
        }
    }
    public record Observation(EffectivePersonaState state, List<PromptPolicyComposer.PromptBlock> blocks,
                              String response) {
        public Observation { blocks = blocks == null ? List.of() : List.copyOf(blocks); response = response == null ? "" : response; }
    }
    public record Case(String personaRevision, Relationship maximumRelationship, Mode expectedMode,
                       SceneState.Type expectedScene, String requiredSceneBehavior,
                       Set<String> forbiddenRelationshipMarkers, Set<String> memoryFactMarkers,
                       Set<String> commitmentMarkers, Set<String> repairMarkers) {
        public Case {
            Objects.requireNonNull(personaRevision); Objects.requireNonNull(maximumRelationship); Objects.requireNonNull(expectedMode);
            forbiddenRelationshipMarkers = copy(forbiddenRelationshipMarkers); memoryFactMarkers = copy(memoryFactMarkers);
            commitmentMarkers = copy(commitmentMarkers); repairMarkers = copy(repairMarkers);
            if (expectedScene != null && (requiredSceneBehavior == null || requiredSceneBehavior.isBlank()))
                throw new IllegalArgumentException("scene behavior is required when a scene is expected");
        }
        private static Set<String> copy(Set<String> value) { return value == null ? Set.of() : Set.copyOf(value); }
    }
}
