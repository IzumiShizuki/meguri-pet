package com.meguri.core.training;

import com.meguri.core.runtime.TurnOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class TrainingFeedbackController {
    private final TurnOrchestrator orchestrator;

    public TrainingFeedbackController(TurnOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(path = "/v1/training/feedback", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(@RequestBody TrainingFeedbackRequest request) {
        try {
            return orchestrator.submitTrainingFeedback(request);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }
}
