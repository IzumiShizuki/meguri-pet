package com.meguri.core.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.capability.ApprovalService;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.security.CoreIdentityVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/** User-facing resolution endpoint for approval.required Turn events. */
@RestController
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class CapabilityApprovalController {
    private final CapabilityRuntimeFacade runtime;
    private final CoreIdentityVerifier identityVerifier;

    public CapabilityApprovalController(
            CapabilityRuntimeFacade runtime,
            CoreIdentityVerifier identityVerifier) {
        this.runtime = runtime;
        this.identityVerifier = identityVerifier;
    }

    @PostMapping("/v1/approvals/{approvalId}:resolve")
    public Mono<ResolutionResponse> resolve(
            @PathVariable String approvalId,
            @RequestBody ResolutionRequest request,
            org.springframework.web.server.ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            ApprovalService.Approval pending = runtime.findApproval(approvalId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "approval not found"));
            if (!identityVerifier.tenantId().equals(pending.tenantId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "approval tenant mismatch");
            }
            CoreIdentityVerifier.Identity identity = identityVerifier.verifyScope(
                    exchange, pending.userId(), pending.clientId(), null);
            ApprovalService.Decision decision = request == null
                    ? null : request.decision();
            if (decision == null || decision == ApprovalService.Decision.PENDING) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "accept, decline, or cancel is required");
            }
            ApprovalService.Approval resolved = runtime.resolveApproval(
                    approvalId, decision,
                    "user:" + identity.userId() + "@" + identity.clientId());
            return new ResolutionResponse(
                    resolved.approvalId(), resolved.capabilityId(),
                    resolved.decision());
        });
    }

    @ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> conflict(
            IllegalStateException failure) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "code", "APPROVAL_ALREADY_RESOLVED",
                        "message", failure.getMessage() == null
                                ? "approval is already resolved"
                                : failure.getMessage()));
    }

    public record ResolutionRequest(
            @JsonProperty("decision") ApprovalService.Decision decision) { }

    public record ResolutionResponse(
            @JsonProperty("approval_id") String approvalId,
            @JsonProperty("capability_id") String capabilityId,
            @JsonProperty("decision") ApprovalService.Decision decision) { }
}
