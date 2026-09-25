package com.meguri.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceContractBindingTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void executionModeDecisionsRoundTripFrozenV1Fixture() throws Exception {
        Path fixture = Path.of("..", "..", "contracts", "performance", "v1",
                "fixtures", "mode-decisions.json").toAbsolutePath().normalize();
        JsonNode decisions = mapper.readTree(Files.readString(fixture)).path("decisions");

        for (JsonNode source : decisions) {
            ExecutionModeDecision decision = mapper.treeToValue(
                    source, ExecutionModeDecision.class);
            JsonNode roundTrip = mapper.valueToTree(decision);
            assertThat(roundTrip.toString()).isEqualTo(source.toString());
        }

        assertThat(mapper.treeToValue(decisions.get(0), ExecutionModeDecision.class).mode())
                .isEqualTo(TurnExecutionMode.FAST);
        assertThat(mapper.treeToValue(decisions.get(2), ExecutionModeDecision.class).budget().maxRounds())
                .isEqualTo(3);
    }

    @Test
    void currentTurnSignalsUseFrozenSnakeCaseFields() {
        CurrentTurnSignals signals = new CurrentTurnSignals(
                TurnExecutionMode.FAST,
                null,
                TurnExecutionMode.AGENT,
                List.of("Greeting", "greeting"),
                false,
                false,
                true,
                false,
                1_500);

        JsonNode json = mapper.valueToTree(signals);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(Set.of(
                "user_requested_mode", "skill_required_mode",
                "classifier_candidate_mode", "intent_tags",
                "external_observation_required", "multi_step_execution_required",
                "tool_capable_client", "remote_agent_capable_client",
                "remaining_deadline_millis"));
        assertThat(json.path("user_requested_mode").asText()).isEqualTo("FAST");
        assertThat(json.path("intent_tags").findValuesAsText("ignored"))
                .isEmpty();
        assertThat(signals.intentTags()).containsExactly("greeting");
    }

    @Test
    void budgetNeverExpandsAndKeepsEarliestAbsoluteDeadline() {
        ExecutionBudget requested = budget(10, 8, Instant.parse("2026-08-01T12:01:00Z"));
        ExecutionBudget ceiling = budget(3, 5, Instant.parse("2026-08-01T12:00:30Z"));

        ExecutionBudget clipped = requested.clipTo(ceiling);

        assertThat(clipped.maxRounds()).isEqualTo(3);
        assertThat(clipped.maxToolCalls()).isEqualTo(3);
        assertThat(clipped.maxTokens()).isEqualTo(5);
        assertThat(clipped.deadlineAt()).isEqualTo(ceiling.deadlineAt());
    }

    private static ExecutionBudget budget(int bounded, long tokens, Instant deadline) {
        return new ExecutionBudget(bounded, bounded, bounded, bounded, bounded,
                bounded, bounded, bounded, tokens, bounded, deadline);
    }
}
