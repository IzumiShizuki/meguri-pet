package com.meguri.core.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputModelCheckerFactoryTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void absentInputModelDoesNotImplicitlyCallPlannerOrAnswerModel() {
        var checker = InputModelCheckerFactory.create(Map.of("MEGURI_AGENT_PLANNER_MODEL", "planner",
                "MEGURI_LLM_MODEL", "answer"), mapper);
        assertThat(checker.status().state()).isEqualTo("disabled");
        assertThat(checker.status().model()).isEmpty();
    }

    @Test void configuredRouteCanBeExplicitlyDisabled() {
        var checker = InputModelCheckerFactory.create(Map.of("MEGURI_INPUT_CHECK_MODEL", "small",
                "MEGURI_INPUT_CHECK_ENABLED", "false"), mapper);
        assertThat(checker.status().state()).isEqualTo("disabled");
    }

    @Test void loopbackRouteNeedsNoSecretAndReportsItsOwnModel() {
        var checker = InputModelCheckerFactory.create(Map.of("MEGURI_INPUT_CHECK_MODEL", "input-small",
                "MEGURI_INPUT_CHECK_BASE_URL", "http://127.0.0.1:12345/v1"), mapper);
        assertThat(checker.status().state()).isEqualTo("enabled");
        assertThat(checker.status().model()).isEqualTo("input-small");
    }

    @Test void remoteRouteRequiresHttpsAndReadableSecretFile() throws Exception {
        Path key = temporary.resolve("input-key.txt");
        Files.writeString(key, "fixture-api-key");
        var env = new java.util.HashMap<>(Map.of("MEGURI_INPUT_CHECK_MODEL", "small",
                "MEGURI_INPUT_CHECK_BASE_URL", "https://llm.example/v1",
                "MEGURI_INPUT_CHECK_API_KEY_FILE", key.toAbsolutePath().toString()));
        assertThat(InputModelCheckerFactory.create(env, mapper).status().state()).isEqualTo("enabled");
        env.put("MEGURI_INPUT_CHECK_BASE_URL", "http://llm.example/v1");
        assertThatThrownBy(() -> InputModelCheckerFactory.create(env, mapper)).isInstanceOf(IllegalArgumentException.class);
        env.put("MEGURI_INPUT_CHECK_BASE_URL", "https://llm.example/v1");
        env.put("MEGURI_INPUT_CHECK_API_KEY_FILE", "relative-secret.txt");
        assertThatThrownBy(() -> InputModelCheckerFactory.create(env, mapper)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidBudgetsAndInlineCredentialFailConfiguration() {
        var env = new java.util.HashMap<>(Map.of("MEGURI_INPUT_CHECK_MODEL", "small",
                "MEGURI_INPUT_CHECK_BASE_URL", "http://localhost:12345/v1"));
        env.put("MEGURI_INPUT_CHECK_TIMEOUT_MS", "90000");
        assertThatThrownBy(() -> InputModelCheckerFactory.create(env, mapper)).isInstanceOf(IllegalArgumentException.class);
        env.remove("MEGURI_INPUT_CHECK_TIMEOUT_MS");
        env.put("MEGURI_INPUT_CHECK_API_KEY", "fixture-inline-key");
        assertThatThrownBy(() -> InputModelCheckerFactory.create(env, mapper)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("fixture-inline-key");
    }
}
