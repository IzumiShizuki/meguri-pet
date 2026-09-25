package com.meguri.core.skill;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SkillManagementService {
    private final SkillSourceRegistry sources;
    private final SkillCatalogRepository repository;
    private final SkillPackageValidator validator;
    private final SkillPackageStore packages;

    public SkillManagementService(
            SkillSourceRegistry sources,
            SkillCatalogRepository repository,
            SkillPackageValidator validator,
            SkillPackageStore packages) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.packages = Objects.requireNonNull(packages, "packages");
    }

    public SkillSourcePlugin.SearchPage search(String sourceId, String query, int page, int pageSize) {
        return sources.require(sourceId).search(query, Math.max(1, page), Math.max(1, Math.min(50, pageSize)));
    }
    public SkillSourcePlugin.Detail detail(String sourceId, String externalId) {
        return sources.require(sourceId).detail(externalId);
    }
    public List<SkillCatalogEntry> list() { return repository.list(); }
    public SkillCatalogEntry get(String id) { return repository.find(id).orElseThrow(() -> new SkillException("SKILL_NOT_FOUND", "Skill does not exist")); }
    public List<SkillAuditEvent> audit() { return repository.audit(); }

    public SkillCatalogEntry importSkill(String sourceId, String externalId, String actor) {
        SkillSourcePlugin source = sources.require(sourceId);
        SkillSourcePlugin.Detail detail = source.detail(externalId);
        SkillSourcePlugin.FetchResult fetched = source.fetch(externalId);
        try {
        String sourceRevision = fetched.revision() == null || fetched.revision().isBlank()
                ? detail.summary().revision() : fetched.revision().trim();
        String skillId = stableId(sourceId, externalId);
        SkillSourcePlugin.Summary remote = detail.summary();
        SkillManifest remoteManifest = new SkillManifest(
                skillId, sourceId, externalId,
                remote.name(), remote.description(), remote.license(), remote.tags(),
                detail.requirements(), false, sourceRevision, remote.updatedAt());
        repository.saveManifest(remoteManifest);
        Instant importedAt = Instant.now();
        String digest = packages.fingerprint(fetched.stagingPath());
        SkillValidationReport quarantineReport = new SkillValidationReport(
                SkillValidationReport.ValidationState.QUARANTINED,
                false,
                false,
                List.of(),
                importedAt);
        SkillRevision quarantined = new SkillRevision(skillId, digest,
                "quarantine:" + digest, sourceRevision, remoteManifest, quarantineReport, importedAt);
        repository.saveRevision(quarantined);
        repository.saveBinding(repository.find(skillId).map(SkillCatalogEntry::binding)
                .orElse(new SkillBinding(skillId, null, SkillBinding.ActivationState.DISABLED, Instant.now())));
        audit(remoteManifest, digest, actor, "IMPORT", "QUARANTINED", null);

        SkillPackageValidator.ValidationResult validation = validator.validate(fetched.stagingPath());
        SkillPackageValidator.Frontmatter local = validation.frontmatter();
        SkillManifest manifest = new SkillManifest(
                skillId, sourceId, externalId,
                local == null ? remote.name() : local.name(),
                local == null || local.description() == null ? remote.description() : local.description(),
                local == null || local.license() == null ? remote.license() : local.license(),
                local == null || local.tags().isEmpty() ? remote.tags() : local.tags(),
                local == null ? detail.requirements() : local.requires(),
                local != null && local.always(), sourceRevision, remote.updatedAt());
        repository.saveManifest(manifest);
        SkillPackageStore.StoredPackage stored = null;
        if (validation.report().state() == SkillValidationReport.ValidationState.VALID) {
            stored = packages.ingest(fetched.stagingPath(), digest);
        }

        SkillRevision revision = new SkillRevision(skillId, digest,
                stored == null ? "quarantine:" + digest : stored.path().toString(),
                sourceRevision, manifest, validation.report(), importedAt);
        repository.saveRevision(revision);
        audit(manifest, digest, actor, "VALIDATE", validation.report().state().name(),
                firstFailure(validation.report()));
        return get(skillId);
        } finally {
            try { source.release(fetched); }
            catch (RuntimeException ignored) {
                // The immutable package/catalog result is authoritative; staging cleanup is best-effort.
            }
        }
    }

    public SkillCatalogEntry activate(String skillId, String digest, boolean licenseConfirmed, String actor) {
        SkillCatalogEntry entry = get(skillId);
        SkillRevision revision = entry.revisions().stream().filter(v -> v.digest().equalsIgnoreCase(digest))
                .findFirst().orElseThrow(() -> new SkillException("SKILL_REVISION_NOT_FOUND", "Skill revision does not exist"));
        if (!revision.report().validAndCompatible()) throw new SkillException("SKILL_REVISION_NOT_COMPATIBLE", "Skill revision is not valid and compatible");
        SkillManifest revisionManifest = revision.manifest() == null
                ? entry.manifest() : revision.manifest();
        if (revisionManifest.license() == null && !licenseConfirmed) {
            throw new SkillException("SKILL_LICENSE_CONFIRMATION_REQUIRED", "missing license requires explicit confirmation");
        }
        // Activation and rollback re-verify the content-addressed package so a
        // deleted or locally modified revision can never become eligible.
        packages.resolve(revision.digest(), "SKILL.md");
        repository.saveBinding(new SkillBinding(skillId, revision.digest(), SkillBinding.ActivationState.ENABLED, Instant.now()));
        audit(entry.manifest(), revision.digest(), actor, "ACTIVATE", "ENABLED", null);
        return get(skillId);
    }

    public SkillCatalogEntry disable(String skillId, String actor) {
        SkillCatalogEntry entry = get(skillId);
        repository.saveBinding(new SkillBinding(skillId, entry.binding().activeDigest(), SkillBinding.ActivationState.DISABLED, Instant.now()));
        audit(entry.manifest(), entry.binding().activeDigest(), actor, "DISABLE", "DISABLED", null);
        return get(skillId);
    }

    public SkillSourcePlugin.UpdateStatus checkUpdate(String skillId, String actor) {
        SkillCatalogEntry entry = get(skillId);
        SkillRevision active = entry.activeRevision();
        String current = active == null ? entry.manifest().sourceRevision() : active.sourceRevision();
        SkillSourcePlugin.UpdateStatus status = sources.require(entry.manifest().sourceId())
                .checkUpdate(entry.manifest().externalId(), current);
        audit(entry.manifest(), active == null ? null : active.digest(), actor, "CHECK_UPDATE",
                status.updateAvailable() ? "UPDATE_AVAILABLE" : "CURRENT", null);
        return status;
    }

    public SkillCatalogEntry importUpdate(String skillId, String actor) {
        SkillCatalogEntry entry = get(skillId);
        return importSkill(entry.manifest().sourceId(), entry.manifest().externalId(), actor);
    }

    public java.nio.file.Path packageFile(String digest, String relativePath) {
        return packages.resolve(digest, relativePath);
    }

    private void audit(SkillManifest manifest, String digest, String actor,
                       String transition, String status, String failureCode) {
        repository.appendAudit(new SkillAuditEvent(
                "skill_audit_" + UUID.randomUUID().toString().replace("-", ""),
                manifest.sourceId(), manifest.externalId(), manifest.id(), digest,
                actor == null || actor.isBlank() ? "unknown" : actor.trim(), transition, status, failureCode, Instant.now()));
    }

    private static String firstFailure(SkillValidationReport report) {
        return report.issues().stream().filter(v -> v.severity() == SkillValidationReport.Severity.ERROR)
                .map(SkillValidationReport.Issue::code).findFirst().orElse(null);
    }
    private static String stableId(String source, String external) { return "skill_" + hash(source + "\n" + external).substring(0, 24); }
    private static String hash(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class SkillException extends RuntimeException {
        private final String code;
        public SkillException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
