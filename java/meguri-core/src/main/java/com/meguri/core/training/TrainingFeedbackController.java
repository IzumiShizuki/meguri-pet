package com.meguri.core.training;

import com.meguri.core.runtime.TurnOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.meguri.core.memory.DesktopMemoryIdentity;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class TrainingFeedbackController {
    private final TurnOrchestrator orchestrator;
    private final DesktopMemoryIdentity identity;
    private final TrainingFeedbackService feedback;

    public TrainingFeedbackController(TurnOrchestrator orchestrator, DesktopMemoryIdentity identity,
                                      TrainingFeedbackService feedback) {
        this.orchestrator = orchestrator;
        this.identity = identity;
        this.feedback = feedback;
    }

    @PostMapping(path = {"/v1/training/feedback", "/v1/training/desktop/feedback"}, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(@RequestBody TrainingFeedbackRequest request, ServerWebExchange exchange) {
        try {
            var scope = "/v1/training/feedback".equals(exchange.getRequest().getPath().value())
                    ? identity.verifyTraining(exchange) : identity.verify(exchange);
            return orchestrator.submitTrainingFeedback(request, scope, requestId(exchange));
        } catch (IllegalArgumentException error) {
            throw rejected(error);
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "training_feedback_storage_unavailable");
        }
    }

    @GetMapping(path = "/v1/training/desktop/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pending(ServerWebExchange exchange) {
        try { return feedback.pending(identity.verify(exchange)); }
        catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "training_feedback_storage_unavailable");
        }
    }

    @PostMapping(path = "/v1/training/desktop/feedback/{feedbackId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> revoke(@PathVariable String feedbackId, @RequestBody JsonNode body,
                                      ServerWebExchange exchange) {
        var scope = identity.verify(exchange);
        if (!body.isObject() || !body.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_training_request");
        try { return feedback.revoke(feedbackId, scope, requestId(exchange)); }
        catch (IllegalArgumentException error) { throw rejected(error); }
        catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "training_feedback_storage_unavailable");
        }
    }

    private static String requestId(ServerWebExchange exchange) {
        String value = exchange.getRequest().getHeaders().getFirst("Idempotency-Key");
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,200}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feedback_request_id_required");
        return value;
    }

    private static ResponseStatusException rejected(IllegalArgumentException error) {
        String code = error.getMessage();
        HttpStatus status = switch (code) {
            case "training_scope_mismatch" -> HttpStatus.FORBIDDEN;
            case "training_comparison_unavailable", "training_feedback_unavailable" -> HttpStatus.NOT_FOUND;
            case "feedback_idempotency_conflict" -> HttpStatus.CONFLICT;
            case "feedback_request_id_required" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return new ResponseStatusException(status, code);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> error(ResponseStatusException error) {
        String code = error.getReason();
        if (code == null || !code.matches("[a-z_]{1,80}")) code = "training_feedback_invalid";
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("detail", Map.of("code", code)));
    }
}
