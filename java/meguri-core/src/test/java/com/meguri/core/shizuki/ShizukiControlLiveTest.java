package com.meguri.core.shizuki;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in E2E against the current user's running Shizuki desktop process. */
@EnabledIfEnvironmentVariable(named = "MEGURI_RUN_SHIZUKI_E2E", matches = "(?i)true")
class ShizukiControlLiveTest {
    @Test
    void readsStateAndShowsTheLocalShizukiWindow() {
        LoopbackShizukiControlClient client = new LoopbackShizukiControlClient(
                ShizukiControlConfiguration.resolveManifestPath(""),
                new ObjectMapper(), Duration.ofMillis(750), Duration.ofSeconds(2));

        Map<String, Object> state = client.readState();
        Map<String, Object> command = client.execute("window.show", Map.of());

        assertThat(state).containsEntry("status", "online");
        assertThat(state.toString()).doesNotContain("token", "authorization");
        assertThat(command)
                .containsEntry("status", "executed")
                .containsEntry("command", "window.show");
        assertThat(command.toString()).doesNotContain("token", "authorization");
    }
}
