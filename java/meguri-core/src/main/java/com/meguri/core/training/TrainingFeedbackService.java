package com.meguri.core.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/** Captures explicit preference pairs and behavior corrections as local training candidates. */
@Service
public final class TrainingFeedbackService {
    private static final int MAX_PENDING_TURNS = 500;
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
    private final Map<String, Comparison> comparisons = new ConcurrentHashMap<>();

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
        return comparisons.containsKey(turnId);
    }

    public Submission submit(TrainingFeedbackRequest request) {
        if (request == null || request.turnId() == null || request.turnId().isBlank()) {
            throw new IllegalArgumentException("turn_id is required");
        }
        if (!request.consentToTraining()) {
            throw new IllegalArgumentException("explicit training consent is required");
        }
        Comparison comparison = comparisons.get(request.turnId());
        if (comparison == null) throw new IllegalArgumentException("training comparison is unavailable");
        String feedback = request.feedbackText() == null ? "" : request.feedbackText().trim();
        if (feedback.length() > 2000) throw new IllegalArgumentException("feedback_text is too long");
        Candidate selected = comparison.candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(request.selectedCandidateId()))
                .findFirst().orElse(null);
        if (selected == null && feedback.isBlank()) {
            throw new IllegalArgumentException("select a candidate or provide feedback");
        }

        String feedbackId = "feedback-" + UUID.randomUUID();
        List<String> rejectedIds = selected == null ? List.of() : comparison.candidates().stream()
                .map(Candidate::candidateId)
                .filter(candidateId -> !candidateId.equals(selected.candidateId()))
                .toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema_version", "meguri-behavior-feedback-v1");
        row.put("feedback_id", feedbackId);
        row.put("captured_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        row.put("status", "training_candidate");
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
        row.put("candidates", comparison.candidates().stream().map(candidate -> Map.of(
                "candidate_id", candidate.candidateId(),
                "response", candidate.response())).toList());
        row.put("provenance", Map.of(
                "source", "desktop_active_learning",
                "model_provider", comparison.modelProvider(),
                "build_id", comparison.buildId(),
                "comparison_id", comparison.comparisonId()));
        append(row);
        comparisons.remove(request.turnId(), comparison);
        return new Submission(feedbackId, comparison, selected);
    }

    public String feedbackFileDisplay() {
        return feedbackFile.toString();
    }

    private synchronized void append(Map<String, Object> row) {
        try {
            Path parent = feedbackFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(feedbackFile, mapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) {
            throw new IllegalStateException("failed to persist training feedback", error);
        }
    }

    private void remember(Comparison comparison) {
        comparisons.put(comparison.turnId(), comparison);
        int excess = comparisons.size() - MAX_PENDING_TURNS;
        if (excess <= 0) return;
        comparisons.keySet().stream()
                .filter(turnId -> !turnId.equals(comparison.turnId()))
                .limit(excess)
                .forEach(comparisons::remove);
    }

    private static double probability(double value, String label) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be between 0 and 1");
        }
        return value;
    }
}
