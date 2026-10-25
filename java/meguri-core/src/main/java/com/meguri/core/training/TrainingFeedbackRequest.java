package com.meguri.core.training;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.dto.LlmResponse;

/** User-owned supervision for one sampled turn. */
public record TrainingFeedbackRequest(
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("selected_candidate_id") String selectedCandidateId,
        @JsonProperty("feedback_text") String feedbackText,
        @JsonProperty("consent_to_training") boolean consentToTraining,
        @JsonProperty("corrected_response") LlmResponse correctedResponse) {
    public TrainingFeedbackRequest(String turnId, String selectedCandidateId, String feedbackText,
                                   boolean consentToTraining) {
        this(turnId, selectedCandidateId, feedbackText, consentToTraining, null);
    }
}
