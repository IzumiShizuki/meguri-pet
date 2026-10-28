package com.meguri.core.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.memory.DesktopMemoryIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TrainingFeedbackRecoveryTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DesktopMemoryIdentity.Scope scope = new DesktopMemoryIdentity.Scope(
            "tenant-a", "user-a", "desktop_pet", "session-a", false);

    @Test
    void pendingReceiptsRetriesAndWithdrawalSurviveReconstruction() throws Exception {
        Path output = tempDir.resolve("feedback.jsonl");
        var first = service(output);
        first.registerSingle("turn-a", request(), List.of("recent"), new LlmResponse("answer"), "mock", "build-a");
        var restarted = service(output);
        assertThat((List<?>) restarted.pending(scope).get("pending")).hasSize(1);
        var request = new TrainingFeedbackRequest("turn-a", null, "请先给出可执行步骤", true);
        var saved = restarted.submit(request, scope, "save-a");
        assertThat(service(output).submit(request, scope, "save-a").feedbackId()).isEqualTo(saved.feedbackId());
        assertThat(Files.readAllLines(output)).hasSize(1);
        var row = mapper.readTree(Files.readString(output));
            assertThat(row.path("consent_to_training").asBoolean()).isTrue();
            assertThat(row.path("consent_version").asText()).isEqualTo("meguri-training-consent-v1");
            assertThat(row.path("tenant_id").asText()).isEqualTo("tenant-a");
            assertThat(row.path("provenance").path("source_service").asText()).isEqualTo(TrainingFeedbackService.class.getName());
        var revoke = restarted.revoke(saved.feedbackId(), scope, "revoke-a");
        assertThat(revoke).containsEntry("turn_id", "turn-a").containsEntry("status", "revoked");
        assertThat(service(output).revoke(saved.feedbackId(), scope, "revoke-a")).isEqualTo(revoke);
        assertThat(Files.readAllLines(output)).hasSize(2);
        assertThat((List<?>) service(output).pending(scope).get("pending")).isEmpty();
        assertThat((List<?>) service(output).pending(scope).get("receipts")).hasSize(1);
        assertThatThrownBy(() -> service(output).submit(request, scope, "save-a"))
                .hasMessage("feedback_idempotency_conflict");
    }

    @Test
    void ownershipConsentAndIdempotencyAreEnforced() {
        Path output = tempDir.resolve("feedback.jsonl");
        var feedback = service(output);
        feedback.registerSingle("turn-a", request(), List.of(), new LlmResponse("answer"), "mock", "build-a");
        var foreign = new DesktopMemoryIdentity.Scope("tenant-b", "user-a", "desktop_pet", "session-a", true);
        assertThat((List<?>) feedback.pending(foreign).get("pending")).isEmpty();
        assertThatThrownBy(() -> feedback.submit(new TrainingFeedbackRequest("turn-a", null, "note", true), foreign, "key"))
                .hasMessage("training_scope_mismatch");
        assertThatThrownBy(() -> feedback.submit(new TrainingFeedbackRequest("turn-a", null, "note", false), scope, "key"))
                .hasMessage("training_feedback_invalid");
        assertThat(Files.exists(output)).isFalse();
        var saved = feedback.submit(new TrainingFeedbackRequest("turn-a", null, "note", true), scope, "key");
        assertThatThrownBy(() -> feedback.submit(new TrainingFeedbackRequest("turn-a", null, "changed", true), scope, "key"))
                .hasMessage("feedback_idempotency_conflict");
        assertThatThrownBy(() -> feedback.revoke(saved.feedbackId(), foreign, "revoke"))
                .hasMessage("training_scope_mismatch");
        feedback.registerSingle("turn-b", request(), List.of(), new LlmResponse("second"), "mock", "build-a");
        assertThatThrownBy(() -> feedback.submit(new TrainingFeedbackRequest("turn-b", null, "note", true), scope, "key"))
                .hasMessage("feedback_idempotency_conflict");
    }

    @Test
    void failedAppendPreservesComparisonAndRetryCompletesIntentExactlyOnce() throws Exception {
        Path output = tempDir.resolve("feedback.jsonl");
        var feedback = service(output);
        feedback.registerSingle("turn-a", request(), List.of(), new LlmResponse("answer"), "mock", "build-a");
        Files.createDirectory(output);
        var request = new TrainingFeedbackRequest("turn-a", null, "note", true);
        assertThatThrownBy(() -> feedback.submit(request, scope, "save-a"))
                .hasMessage("training_feedback_storage_unavailable");
        assertThat(feedback.hasComparison("turn-a")).isTrue();
        Files.delete(output);
        var saved = service(output).submit(request, scope, "save-a");
        assertThat(service(output).submit(request, scope, "save-a").feedbackId()).isEqualTo(saved.feedbackId());
        assertThat(Files.readAllLines(output)).hasSize(1);
    }

    @Test
    void tornTrailingAppendIsRepairedWithoutDiscardingEarlierFeedback() throws Exception {
        Path output = tempDir.resolve("feedback.jsonl");
        var feedback = service(output);
        feedback.registerSingle("turn-a", request(), List.of(), new LlmResponse("answer"), "mock", "build-a");
        feedback.submit(new TrainingFeedbackRequest("turn-a", null, "one", true), scope, "save-a");
        Files.writeString(output, "{\"feedback_id\":", java.nio.file.StandardOpenOption.APPEND);
        feedback.registerSingle("turn-b", request(), List.of(), new LlmResponse("answer"), "mock", "build-a");
        feedback.submit(new TrainingFeedbackRequest("turn-b", null, "two", true), scope, "save-b");
        var lines = Files.readAllLines(output);
        assertThat(lines).hasSize(2);
        assertThat(mapper.readTree(lines.get(0)).path("feedback_text").asText()).isEqualTo("one");
        assertThat(mapper.readTree(lines.get(1)).path("feedback_text").asText()).isEqualTo("two");
    }

    private TrainingFeedbackService service(Path output) {
        return new TrainingFeedbackService(mapper, output, 1, 0, () -> 0);
    }
    private TurnRequest request() {
        return new TurnRequest("user-a", "desktop_pet", "session-a", null, "message", List.of(),
                new com.meguri.core.dto.ClientCapabilities(), null, null, false, true).withTenantId("tenant-a");
    }
}
