package com.meguri.core.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.memory.DesktopMemoryIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/** Captures explicit preference pairs and behavior corrections as local training candidates. */
@Service
public final class TrainingFeedbackService {
    public static final String SCHEMA_VERSION = "meguri-behavior-feedback-v2";
    public record Candidate(String candidateId, LlmResponse response) { }
    public record Comparison(
            String comparisonId,
            String turnId,
            TurnRequest request,
            List<String> recentContext,
            List<Candidate> candidates,
            boolean trainingMode,
            String modelProvider,
            String buildId) { }
    public record Submission(String feedbackId, Comparison comparison, Candidate selected) { }

    private final ObjectMapper mapper;
    private final Path feedbackFile;
    private final double trainingModeProbability;
    private final double normalModeProbability;
    private final DoubleSupplier random;
    private final TrainingFeedbackArchive archive;

    @Autowired
    public TrainingFeedbackService(
            ObjectMapper mapper,
            @Value("${meguri.training-feedback.file:${user.home}/.meguri/training/behavior-feedback.jsonl}") String feedbackFile,
            @Value("${meguri.training-feedback.training-mode-probability:0.03}") double trainingModeProbability,
            @Value("${meguri.training-feedback.normal-mode-probability:0.001}") double normalModeProbability) {
        this(mapper, Path.of(feedbackFile), trainingModeProbability, normalModeProbability, Math::random);
    }

    public TrainingFeedbackService(ObjectMapper mapper, Path feedbackFile,
                                   double trainingModeProbability, double normalModeProbability,
                                   DoubleSupplier random) {
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper.findAndRegisterModules();
        this.feedbackFile = feedbackFile.toAbsolutePath().normalize();
        this.trainingModeProbability = probability(trainingModeProbability, "training-mode probability");
        this.normalModeProbability = probability(normalModeProbability, "normal-mode probability");
        this.random = random == null ? Math::random : random;
        this.archive = new TrainingFeedbackArchive(this.mapper, this.feedbackFile);
    }

    public static TrainingFeedbackService disabled(ObjectMapper mapper) {
        return new TrainingFeedbackService(mapper,
                Path.of(System.getProperty("java.io.tmpdir"), "meguri-training-feedback-disabled.jsonl"),
                0, 0, () -> 1);
    }

    public boolean shouldCompare(boolean trainingMode) {
        double threshold = trainingMode ? trainingModeProbability : normalModeProbability;
        return threshold > 0 && random.getAsDouble() < threshold;
    }

    public Optional<Map<String, Object>> registerComparison(
            String turnId,
            TurnRequest request,
            List<String> recentContext,
            LlmResponse first,
            LlmResponse second,
            String modelProvider,
            String buildId) {
        if (first.equals(second)) return Optional.empty();
        String comparisonId = "comparison-" + UUID.randomUUID();
        Comparison comparison = new Comparison(
                comparisonId,
                turnId,
                request,
                recentContext == null ? List.of() : recentContext.stream().limit(12).toList(),
                List.of(
                        new Candidate(comparisonId + "-a", first),
                        new Candidate(comparisonId + "-b", second)),
                request.trainingMode(),
                modelProvider,
                buildId);
        remember(comparison);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("comparison_id", comparisonId);
        event.put("training_mode", request.trainingMode());
        event.put("candidates", comparison.candidates().stream().map(candidate -> Map.of(
                "candidate_id", candidate.candidateId(),
                "response", candidate.response())).toList());
        return Optional.of(event);
    }

    public void registerSingle(String turnId, TurnRequest request, List<String> recentContext,
                               LlmResponse response, String modelProvider, String buildId) {
        String comparisonId = "observation-" + UUID.randomUUID();
        remember(new Comparison(
                comparisonId,
                turnId,
                request,
                recentContext == null ? List.of() : recentContext.stream().limit(12).toList(),
                List.of(new Candidate(comparisonId + "-a", response)),
                request.trainingMode(),
                modelProvider,
                buildId));
    }

    public boolean hasComparison(String turnId) {
        try { return archive.locked(() -> archive.find(turnId) != null); }
        catch (IllegalStateException ignored) { return false; }
    }

    public Submission submit(TrainingFeedbackRequest request) {
        return submit(request, null, "legacy-" + payloadDigest(request));
    }

    public Submission submit(TrainingFeedbackRequest request, DesktopMemoryIdentity.Scope scope, String requestId) {
        return archive.locked(() -> submitLocked(request, scope, requireRequestId(requestId)));
    }

    private Submission submitLocked(TrainingFeedbackRequest request, DesktopMemoryIdentity.Scope scope, String requestId) {
        if (request == null || request.turnId() == null || request.turnId().isBlank()) {
            throw new IllegalArgumentException("training_feedback_invalid");
        }
        if (!request.consentToTraining()) {
            throw new IllegalArgumentException("training_feedback_invalid");
        }
        TrainingFeedbackArchive.Entry entry = archive.find(request.turnId());
        if (entry == null) throw new IllegalArgumentException("training_comparison_unavailable");
        verifyScope(entry, scope);
        String payloadDigest = payloadDigest(request);
        // A submission key cannot be rebound to another Turn within the authenticated scope.
        for (var other : archive.entries()) {
            if (sameScope(entry, other) && requestId.equals(other.requestId())
                    && !request.turnId().equals(other.comparison().turnId()))
                throw new IllegalArgumentException("feedback_idempotency_conflict");
        }
        if (entry.feedbackId() != null) {
            if (!requestId.equals(entry.requestId()) || !payloadDigest.equals(entry.payloadDigest()))
                throw new IllegalArgumentException("feedback_idempotency_conflict");
            entry = finishIntent(entry);
            if ("revoked".equals(entry.status())) throw new IllegalArgumentException("feedback_idempotency_conflict");
            return submission(entry);
        }
        Comparison comparison = entry.comparison();
        String feedback = request.feedbackText() == null ? "" : request.feedbackText().trim();
        if (feedback.length() > 2000) throw new IllegalArgumentException("training_feedback_invalid");
        Candidate selected = comparison.candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(request.selectedCandidateId()))
                .findFirst().orElse(null);
        if ((request.selectedCandidateId() != null && !request.selectedCandidateId().isBlank() && selected == null)
                || (selected == null && feedback.isBlank() && request.correctedResponse() == null)) {
            throw new IllegalArgumentException("training_feedback_invalid");
        }

        String feedbackId = "feedback-" + UUID.randomUUID();
        List<String> rejectedIds = selected == null ? List.of() : comparison.candidates().stream()
                .map(Candidate::candidateId)
                .filter(candidateId -> !candidateId.equals(selected.candidateId()))
                .toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema_version", SCHEMA_VERSION);
        row.put("feedback_id", feedbackId);
        String capturedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        row.put("captured_at", capturedAt);
        row.put("status", "training_candidate");
        row.put("consent_to_training", true);
        row.put("consent_version", "meguri-training-consent-v1");
        row.put("consent_captured_at", capturedAt);
        row.put("tenant_id", entry.tenantId());
        row.put("request_id", requestId);
        row.put("training_mode", comparison.trainingMode());
        row.put("user_id", comparison.request().userId());
        row.put("client_id", comparison.request().clientId());
        row.put("session_id", comparison.request().sessionId());
        row.put("turn_id", comparison.turnId());
        row.put("user_message", comparison.request().message());
        row.put("recent_context", comparison.recentContext());
        row.put("selected_candidate_id", selected == null ? null : selected.candidateId());
        row.put("rejected_candidate_ids", rejectedIds);
        row.put("feedback_text", feedback);
        row.put("corrected_response", request.correctedResponse());
        row.put("relationship_profile", comparison.request().relationshipProfile());
        row.put("reply_format", comparison.request().replyFormat());
        row.put("candidates", comparison.candidates().stream().map(candidate -> Map.of(
                "candidate_id", candidate.candidateId(),
                "response", candidate.response())).toList());
        row.put("provenance", Map.of(
                "source", "desktop_active_learning",
                "source_service", TrainingFeedbackService.class.getName(),
                "model_provider", comparison.modelProvider(),
                "build_id", comparison.buildId(),
                "comparison_id", comparison.comparisonId()));
        var intent = new TrainingFeedbackArchive.Entry(comparison, entry.tenantId(), feedbackId,
                requestId, payloadDigest, selected == null ? null : selected.candidateId(),
                "submission_pending", row, null, null);
        archive.save(intent);
        return submission(finishIntent(intent));
    }

    public Map<String, Object> pending(DesktopMemoryIdentity.Scope scope) {
        return archive.locked(() -> {
            List<Map<String, Object>> pending = new java.util.ArrayList<>();
            List<Map<String, Object>> receipts = new java.util.ArrayList<>();
            for (var entry : archive.entries()) {
                if (!belongsTo(entry, scope)) continue;
                entry = finishIntent(entry);
                if (entry.feedbackId() == null) {
                    Comparison comparison = entry.comparison();
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("turn_id", comparison.turnId());
                    value.put("comparison_id", comparison.comparisonId());
                    value.put("training_mode", comparison.trainingMode());
                    value.put("candidates", comparison.candidates().stream().map(candidate -> Map.of(
                            "candidate_id", candidate.candidateId(), "response", candidate.response())).toList());
                    pending.add(value);
                } else receipts.add(receipt(entry));
            }
            return Map.of("pending", pending, "receipts", receipts);
        });
    }

    public Map<String, Object> revoke(String feedbackId, DesktopMemoryIdentity.Scope scope, String requestId) {
        return archive.locked(() -> {
            requireRequestId(requestId);
            var entry = archive.entries().stream().filter(value -> feedbackId.equals(value.feedbackId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("training_feedback_unavailable"));
            verifyScope(entry, scope);
            entry = finishIntent(entry);
            if ("revoked".equals(entry.status())) return receipt(entry);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema_version", SCHEMA_VERSION);
            row.put("feedback_id", feedbackId);
            row.put("status", "revoked");
            row.put("consent_to_training", false);
            row.put("consent_version", "meguri-training-consent-v1");
            row.put("revoked_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
            row.put("request_id", requestId);
            row.put("tenant_id", entry.tenantId());
            row.put("user_id", entry.comparison().request().userId());
            row.put("client_id", entry.comparison().request().clientId());
            row.put("session_id", entry.comparison().request().sessionId());
            row.put("turn_id", entry.comparison().turnId());
            var intent = new TrainingFeedbackArchive.Entry(entry.comparison(), entry.tenantId(), entry.feedbackId(),
                    entry.requestId(), entry.payloadDigest(), entry.selectedCandidateId(), "revocation_pending",
                    entry.submissionRow(), requestId, row);
            archive.save(intent);
            return receipt(finishIntent(intent));
        });
    }

    private TrainingFeedbackArchive.Entry finishIntent(TrainingFeedbackArchive.Entry entry) {
        String completed;
        if ("submission_pending".equals(entry.status())) {
            archive.appendOnce(entry.submissionRow()); completed = "training_candidate_saved";
        } else if ("revocation_pending".equals(entry.status())) {
            archive.appendOnce(entry.revokeRow()); completed = "revoked";
        } else return entry;
        var finished = new TrainingFeedbackArchive.Entry(entry.comparison(), entry.tenantId(), entry.feedbackId(),
                entry.requestId(), entry.payloadDigest(), entry.selectedCandidateId(), completed,
                entry.submissionRow(), entry.revokeRequestId(), entry.revokeRow());
        archive.save(finished);
        return finished;
    }

    private static Submission submission(TrainingFeedbackArchive.Entry entry) {
        Candidate selected = entry.comparison().candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(entry.selectedCandidateId())).findFirst().orElse(null);
        return new Submission(entry.feedbackId(), entry.comparison(), selected);
    }

    private static Map<String, Object> receipt(TrainingFeedbackArchive.Entry entry) {
        return Map.of("feedback_id", entry.feedbackId(), "turn_id", entry.comparison().turnId(), "status", entry.status());
    }

    private static boolean belongsTo(TrainingFeedbackArchive.Entry entry, DesktopMemoryIdentity.Scope scope) {
        return scope != null && scope.tenantId().equals(entry.tenantId())
                && scope.userId().equals(entry.comparison().request().userId())
                && scope.clientId().equals(entry.comparison().request().clientId())
                && scope.sessionId().equals(entry.comparison().request().sessionId());
    }

    private static void verifyScope(TrainingFeedbackArchive.Entry entry, DesktopMemoryIdentity.Scope scope) {
        if (scope != null && !belongsTo(entry, scope)) throw new IllegalArgumentException("training_scope_mismatch");
    }

    private static boolean sameScope(TrainingFeedbackArchive.Entry first, TrainingFeedbackArchive.Entry second) {
        return first.tenantId().equals(second.tenantId())
                && first.comparison().request().userId().equals(second.comparison().request().userId())
                && first.comparison().request().clientId().equals(second.comparison().request().clientId())
                && first.comparison().request().sessionId().equals(second.comparison().request().sessionId());
    }

    private String payloadDigest(TrainingFeedbackRequest request) {
        try { return TrainingFeedbackArchive.digest(mapper.writeValueAsString(request)); }
        catch (Exception error) { throw new IllegalArgumentException("training_feedback_invalid"); }
    }

    private static String requireRequestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,200}"))
            throw new IllegalArgumentException("feedback_request_id_required");
        return value;
    }

    public String feedbackFileDisplay() {
        return feedbackFile.toString();
    }

    private void remember(Comparison comparison) {
        archive.locked(() -> {
            if (archive.find(comparison.turnId()) == null)
                archive.save(new TrainingFeedbackArchive.Entry(comparison, comparison.request().tenantId(),
                        null, null, null, null, "pending", null, null, null));
            return null;
        });
    }

    private static double probability(double value, String label) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be between 0 and 1");
        }
        return value;
    }
}
