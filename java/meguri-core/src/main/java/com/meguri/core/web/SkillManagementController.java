package com.meguri.core.web;

import com.meguri.core.security.CoreIdentityVerifier;
import com.meguri.core.skill.SkillCatalogEntry;
import com.meguri.core.skill.SkillBinding;
import com.meguri.core.skill.SkillManifest;
import com.meguri.core.skill.SkillManagementService;
import com.meguri.core.skill.SkillPackageStore;
import com.meguri.core.skill.SkillRequirement;
import com.meguri.core.skill.SkillRevision;
import com.meguri.core.skill.SkillSourcePlugin;
import com.meguri.core.skill.SkillValidationReport;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Objects;
import java.time.Instant;

@RestController
@RequestMapping("/internal/v1")
public final class SkillManagementController {
    private final SkillManagementService skills;
    private final CoreIdentityVerifier identity;

    public SkillManagementController(SkillManagementService skills, CoreIdentityVerifier identity) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @GetMapping("/skill-sources/modelscope/skills")
    SkillSourcePlugin.SearchPage search(
            ServerWebExchange exchange,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        authorize(exchange, "skill:read");
        return skills.search("modelscope", query, page, pageSize);
    }

    @GetMapping("/skill-sources/modelscope/skills/detail")
    SkillSourcePlugin.Detail detail(
            ServerWebExchange exchange,
            @RequestParam(name = "skill_id") String skillId) {
        authorize(exchange, "skill:read");
        return skills.detail("modelscope", skillId);
    }

    @PostMapping("/skills/imports")
    ResponseEntity<SkillCatalogView> importSkill(
            ServerWebExchange exchange, @RequestBody ImportRequest request) {
        String actor = authorize(exchange, "skill:manage");
        return ResponseEntity.status(HttpStatus.CREATED).body(view(skills.importSkill(
                request.sourceId() == null ? "modelscope" : request.sourceId(),
                required(request.externalId(), "external_id"), actor)));
    }

    @GetMapping("/skills")
    List<SkillCatalogView> list(ServerWebExchange exchange) {
        authorize(exchange, "skill:read"); return skills.list().stream().map(SkillManagementController::view).toList();
    }

    @GetMapping("/skills/{id}")
    SkillCatalogView get(ServerWebExchange exchange, @PathVariable String id) {
        authorize(exchange, "skill:read"); return view(skills.get(id));
    }

    @PostMapping("/skills/{id}/revisions/{digest}:activate")
    SkillCatalogView activate(
            ServerWebExchange exchange, @PathVariable String id, @PathVariable String digest,
            @RequestBody(required = false) ActivationRequest request) {
        String actor = authorize(exchange, "skill:manage");
        return view(skills.activate(id, digest, request != null && request.licenseConfirmed(), actor));
    }

    @PostMapping("/skills/{id}:disable")
    SkillCatalogView disable(ServerWebExchange exchange, @PathVariable String id) {
        return view(skills.disable(id, authorize(exchange, "skill:manage")));
    }

    @PostMapping("/skills/{id}:check-update")
    SkillSourcePlugin.UpdateStatus checkUpdate(ServerWebExchange exchange, @PathVariable String id) {
        return skills.checkUpdate(id, authorize(exchange, "skill:manage"));
    }

    @PostMapping("/skills/{id}:import-update")
    SkillCatalogView importUpdate(ServerWebExchange exchange, @PathVariable String id) {
        return view(skills.importUpdate(id, authorize(exchange, "skill:manage")));
    }

    private String authorize(ServerWebExchange exchange, String scope) {
        CoreIdentityVerifier.Identity principal = identity.requireCapabilityScope(exchange, scope);
        return principal.userId() == null || principal.userId().isBlank() ? "local-user" : principal.userId();
    }

    @ExceptionHandler(SkillManagementService.SkillException.class)
    ResponseEntity<ErrorResponse> skillFailure(SkillManagementService.SkillException error) {
        HttpStatus status = "SKILL_NOT_FOUND".equals(error.code()) || "SKILL_REVISION_NOT_FOUND".equals(error.code())
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ErrorResponse(error.code(), error.getMessage(), false));
    }

    @ExceptionHandler(SkillSourcePlugin.SourceException.class)
    ResponseEntity<ErrorResponse> sourceFailure(SkillSourcePlugin.SourceException error) {
        HttpStatus status = "SKILL_SOURCE_DISABLED".equals(error.code()) ? HttpStatus.SERVICE_UNAVAILABLE
                : "MODELSCOPE_SKILL_NOT_FOUND".equals(error.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ErrorResponse(error.code(), error.getMessage(), error.retryable()));
    }

    @ExceptionHandler(SkillPackageStore.PackageStoreException.class)
    ResponseEntity<ErrorResponse> packageFailure(SkillPackageStore.PackageStoreException error) {
        HttpStatus status = "SKILL_PACKAGE_MISSING".equals(error.code())
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ErrorResponse(error.code(), error.getMessage(), false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "INVALID_SKILL_REQUEST", error.getMessage() == null ? "invalid Skill request" : error.getMessage(), false));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static SkillCatalogView view(SkillCatalogEntry entry) {
        String activeDigest = entry.binding().activeDigest();
        return new SkillCatalogView(
                ManifestView.from(entry.manifest()),
                entry.revisions().stream().map(revision -> RevisionView.from(
                        revision, Objects.equals(activeDigest, revision.digest()))).toList(),
                BindingView.from(entry.binding()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SkillCatalogView(
            ManifestView manifest,
            List<RevisionView> revisions,
            BindingView binding) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManifestView(
            String id,
            String sourceId,
            String externalId,
            String trust,
            String name,
            String description,
            String license,
            List<String> tags,
            SkillRequirement requires,
            boolean always,
            String sourceRevision,
            Instant sourceUpdatedAt) {
        static ManifestView from(SkillManifest value) {
            return new ManifestView(value.id(), value.sourceId(), value.externalId(), value.trust(), value.name(),
                    value.description(), value.license(), value.tags(), value.requires(), value.always(),
                    value.sourceRevision(), value.sourceUpdatedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RevisionView(
            String skillId,
            String digest,
            String sourceRevision,
            ManifestView manifest,
            SkillValidationReport report,
            Instant importedAt,
            boolean active) {
        static RevisionView from(SkillRevision value, boolean active) {
            return new RevisionView(value.skillId(), value.digest(), value.sourceRevision(),
                    value.manifest() == null ? null : ManifestView.from(value.manifest()),
                    value.report(), value.importedAt(), active);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BindingView(
            String skillId,
            SkillBinding.ActivationState state,
            String activeDigest,
            Instant updatedAt) {
        static BindingView from(SkillBinding value) {
            return new BindingView(value.skillId(), value.state(), value.activeDigest(), value.updatedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ImportRequest(String sourceId, String externalId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivationRequest(boolean licenseConfirmed) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ErrorResponse(String code, String message, boolean retryable) { }
}
