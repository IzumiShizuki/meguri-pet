package com.meguri.core.training;

import com.meguri.core.memory.DesktopMemoryIdentity;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingFeedbackControllerTest {
    @TempDir Path temporary;

    @Test
    void legacyAndNativeSubmitRequireHostCredentialScopeConsentAndRetryKey() throws Exception {
        Path token = temporary.resolve("token.txt");
        Files.writeString(token, "fixture-token");
        var identity = new DesktopMemoryIdentity(new CoreIdentityVerifier(false, "tenant-a", "", ""),
                token.toString(), "owner");
        var orchestrator = mock(TurnOrchestrator.class);
        when(orchestrator.submitTrainingFeedback(any(), any(), anyString())).thenReturn(
                Map.of("status", "training_candidate_saved", "feedback_id", "feedback-fixture", "turn_id", "turn-a"));
        var client = WebTestClient.bindToController(new TrainingFeedbackController(orchestrator, identity,
                mock(TrainingFeedbackService.class))).build();
        for (String desktop : new String[]{"airi", "desktop_pet"}) {
            client.post().uri("/v1/training/feedback").header("Authorization", "Bearer fixture-token")
                    .header("X-Meguri-Tenant-ID", "tenant-a").header("X-Meguri-User-ID", "owner")
                    .header("X-Meguri-Client-ID", desktop).header("X-Meguri-Session-ID", "session-a")
                    .header("Idempotency-Key", "retry-" + desktop)
                    .bodyValue(Map.of("turn_id", "turn-a", "feedback_text", "note", "consent_to_training", true))
                    .exchange().expectStatus().isCreated();
            verify(orchestrator).submitTrainingFeedback(any(), argThat(scope -> scope.clientId().equals(desktop)), eq("retry-" + desktop));
        }
        client.post().uri("/v1/training/desktop/feedback").header("Authorization", "Bearer fixture-token")
                .header("X-Meguri-Tenant-ID", "tenant-a").header("X-Meguri-User-ID", "owner")
                .header("X-Meguri-Client-ID", "airi").header("X-Meguri-Session-ID", "session-a")
                .header("Idempotency-Key", "native")
                .bodyValue(Map.of("turn_id", "turn-a", "feedback_text", "note", "consent_to_training", true))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/v1/training/feedback")
                .bodyValue(Map.of("turn_id", "turn-a", "feedback_text", "note", "consent_to_training", true))
                .exchange().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
        client.post().uri("/v1/training/feedback").header("Authorization", "Bearer fixture-token")
                .header("X-Meguri-Tenant-ID", "tenant-a").header("X-Meguri-User-ID", "owner")
                .header("X-Meguri-Client-ID", "desktop_pet").header("X-Meguri-Session-ID", "session-a")
                .bodyValue(Map.of("turn_id", "turn-a", "feedback_text", "note", "consent_to_training", true))
                .exchange().expectStatus().isBadRequest();
        verifyNoMoreInteractions(orchestrator);
    }
}
