package com.meguri.core.training;

import com.fasterxml.jackson.annotation.JsonProperty;

/** User-owned supervision for one sampled turn. */
public record TrainingFeedbackRequest(
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("selected_candidate_id") String selectedCandidateId,
        @JsonProperty("feedback_text") String feedbackText,
        @JsonProperty("consent_to_training") boolean consentToTraining) { }
