package com.meguri.core.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.capability.ApprovalService;
import com.meguri.core.capability.CapabilityAudit;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.McpSourceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Explicitly opt-in administrative surface. All endpoints, including reads,
 * require the configured management credential.
 */
@RestController
@RequestMapping("/internal/v1/capabilities")
@ConditionalOnProperty(
        prefix = "meguri.capability.admin",
        name = "enabled",
        havingValue = "true")
public final class CapabilityRuntimeController {
    static final String ADMIN_HEADER = "X-Meguri-Admin-Token";

    private final CapabilityRuntimeFacade runtime;
    private final McpSourceManager sources;
    private final byte[] managementToken;

    @Autowired
    public CapabilityRuntimeController(
            CapabilityRuntimeFacade runtime,
            McpSourceManager sources,
            @Value("${meguri.capability.admin.token:}") String managementToken) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.managementToken = managementToken == null
                ? new byte[0] : managementToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public List<CapabilityRuntimeFacade.CatalogEntry> list(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential) {
        authorize(credential);
        return runtime.catalogEntries();
    }

    @PostMapping("/mcp/sources")
    public ResponseEntity<McpSourceManager.SourceStatus> registerSource(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @RequestBody McpSourceManager.SourceConfiguration configuration) {
        authorize(credential);
        McpSourceManager.SourceStatus status = sources.register(configuration);
        auditAdmin("REGISTER_SOURCE", "mcp-source." + configuration.id(),
                String.valueOf(status.protocolVersion()), status.state().name());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    @GetMapping("/mcp/sources")
    public List<McpSourceManager.SourceStatus> listSources(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential) {
        authorize(credential);
        return sources.statuses();
    }

    @PostMapping("/mcp/sources/{sourceId}:sync")
    public McpSourceManager.SourceStatus syncSource(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String sourceId) {
        authorize(credential);
        McpSourceManager.SourceStatus status = sources.sync(sourceId);
        auditAdmin("SYNC_SOURCE", "mcp-source." + sourceId,
                String.valueOf(status.protocolVersion()), status.state().name());
        return status;
    }

    @DeleteMapping("/mcp/sources/{sourceId}")
    public McpSourceManager.SourceStatus removeSource(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String sourceId) {
        authorize(credential);
        McpSourceManager.SourceStatus status = sources.remove(sourceId);
        auditAdmin("REMOVE_SOURCE", "mcp-source." + sourceId,
                null, status.state().name());
        return status;
    }

    @PostMapping("/{capabilityId}/versions/{version}:enable")
    public CapabilityMutation enable(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String capabilityId,
            @PathVariable String version) {
        authorize(credential);
        runtime.activate(capabilityId, version);
        auditAdmin("ENABLE", capabilityId, version, "ENABLED");
        return new CapabilityMutation(capabilityId, version, "ENABLED");
    }

    @PostMapping("/{capabilityId}:disable")
    public CapabilityMutation disable(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String capabilityId) {
        authorize(credential);
        runtime.disable(capabilityId);
        auditAdmin("DISABLE", capabilityId, null, "DISABLED");
        return new CapabilityMutation(capabilityId, null, "DISABLED");
    }

    @PostMapping("/{capabilityId}:drain")
    public CapabilityMutation drain(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String capabilityId) {
        authorize(credential);
        runtime.drain(capabilityId);
        auditAdmin("DRAIN", capabilityId, null, "DRAINING");
        return new CapabilityMutation(capabilityId, null, "DRAINING");
    }

    @PostMapping("/{capabilityId}/versions/{version}:health")
    public CapabilityMutation health(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String capabilityId,
            @PathVariable String version,
            @RequestBody HealthRequest request) {
        authorize(credential);
        runtime.setHealth(capabilityId, version,
                Objects.requireNonNull(request.health(), "health"));
        auditAdmin("SET_HEALTH", capabilityId, version, request.health().name());
        return new CapabilityMutation(capabilityId, version, request.health().name());
    }

    @GetMapping("/approvals")
    public List<ApprovalService.Approval> approvals(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @RequestParam(name = "decision", required = false)
            ApprovalService.Decision decision) {
        authorize(credential);
        return runtime.approvals().stream()
                .filter(value -> decision == null || value.decision() == decision)
                .toList();
    }

    @PostMapping("/approvals/{approvalId}:resolve")
    public ApprovalService.Approval resolveApproval(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @PathVariable String approvalId,
            @RequestBody ApprovalResolution request) {
        authorize(credential);
        if (request.decision() == null
                || request.decision() == ApprovalService.Decision.PENDING) {
            throw new IllegalArgumentException("terminal approval decision is required");
        }
        ApprovalService.Approval approval = runtime.resolveApproval(
                approvalId, request.decision(), required(request.actor(), "actor"));
        auditAdmin("RESOLVE_APPROVAL", approval.capabilityId(), null,
                approval.decision().name());
        return approval;
    }

    @GetMapping("/audit")
    public List<CapabilityAudit.Event> audit(
            @RequestHeader(name = ADMIN_HEADER, required = false) String credential,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(name = "capability_id", required = false) String capabilityId) {
        authorize(credential);
        return runtime.auditEvents().stream()
                .filter(value -> traceId == null || traceId.equals(value.traceId()))
                .filter(value -> capabilityId == null
                        || capabilityId.equals(value.capabilityId()))
                .toList();
    }

    @ExceptionHandler(AdminAuthenticationException.class)
    ResponseEntity<ErrorResponse> authenticationFailure() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CAPABILITY_ADMIN_DISABLED",
                        "capability administration is unavailable", false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "INVALID_CAPABILITY_ADMIN_REQUEST",
                failure.getMessage() == null ? "invalid capability request" : failure.getMessage(),
                false));
    }

    @ExceptionHandler(McpSourceManager.McpSourceException.class)
    ResponseEntity<ErrorResponse> sourceFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(
                "MCP_SOURCE_UNAVAILABLE", "MCP source update failed closed", true));
    }

    private void authorize(String provided) {
        if (managementToken.length < 16 || provided == null
                || !MessageDigest.isEqual(
                managementToken, provided.getBytes(StandardCharsets.UTF_8))) {
            throw new AdminAuthenticationException();
        }
    }

    private void auditAdmin(
            String action, String capabilityId, String version, String status) {
        runtime.recordAdministrativeAction(
                "management-api", action, capabilityId, version, status);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record CapabilityMutation(
            @JsonProperty("capability_id") String capabilityId,
            @JsonProperty("version") String version,
            @JsonProperty("state") String state) { }

    public record HealthRequest(
            @JsonProperty("health") CapabilityDescriptor.Health health) { }

    public record ApprovalResolution(
            @JsonProperty("decision") ApprovalService.Decision decision,
            @JsonProperty("actor") String actor) { }

    public record ErrorResponse(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("retryable") boolean retryable) { }

    private static final class AdminAuthenticationException extends RuntimeException { }
}
