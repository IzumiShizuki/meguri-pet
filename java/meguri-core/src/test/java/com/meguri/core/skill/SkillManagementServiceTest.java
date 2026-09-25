package com.meguri.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("External Skill catalog lifecycle")
class SkillManagementServiceTest {
    @TempDir Path temporary;
    private MutableSource source;
    private InMemorySkillCatalogRepository repository;
    private SkillManagementService management;

    @BeforeEach
    void setUp() throws Exception {
        source = new MutableSource("modelscope", temporary.resolve("source"));
        repository = new InMemorySkillCatalogRepository();
        management = new SkillManagementService(
                new SkillSourceRegistry(List.of(source)), repository,
                new SkillPackageValidator(Set.of(), name -> false),
                new SkillPackageStore(temporary.resolve("data")));
    }

    @Test
    void should_import_disabled_then_require_license_confirmation_before_activation() throws Exception {
        source.publish("rev-1", null, "PDF workflows");

        SkillCatalogEntry imported = management.importSkill("modelscope", source.externalId(), "alice");

        assertThat(imported.binding().state()).isEqualTo(SkillBinding.ActivationState.DISABLED);
        assertThat(imported.revisions()).singleElement().satisfies(revision -> {
            assertThat(revision.report().state()).isEqualTo(SkillValidationReport.ValidationState.VALID);
            assertThat(revision.report().redistributable()).isFalse();
            assertThat(revision.manifest().sourceRevision()).isEqualTo("rev-1");
        });
        String digest = imported.revisions().getFirst().digest();
        assertThatThrownBy(() -> management.activate(imported.manifest().id(), digest, false, "alice"))
                .isInstanceOf(SkillManagementService.SkillException.class)
                .extracting(error -> ((SkillManagementService.SkillException) error).code())
                .isEqualTo("SKILL_LICENSE_CONFIRMATION_REQUIRED");

        SkillCatalogEntry enabled = management.activate(imported.manifest().id(), digest, true, "alice");

        assertThat(enabled.activeRevision()).isNotNull();
        assertThat(enabled.binding().state()).isEqualTo(SkillBinding.ActivationState.ENABLED);
        assertThat(repository.audit()).extracting(SkillAuditEvent::transition)
                .containsExactly("IMPORT", "VALIDATE", "ACTIVATE");
        assertThat(repository.audit()).extracting(SkillAuditEvent::status)
                .startsWith("QUARANTINED", "VALID");
    }

    @Test
    void should_keep_old_revision_active_until_update_is_confirmed_and_allow_rollback() throws Exception {
        source.publish("rev-1", "MIT", "PDF workflows v1");
        SkillCatalogEntry firstImport = management.importSkill("modelscope", source.externalId(), "alice");
        String firstDigest = firstImport.revisions().getFirst().digest();
        management.activate(firstImport.manifest().id(), firstDigest, false, "alice");
        source.publish("rev-2", null, "PDF workflows v2");

        SkillCatalogEntry update = management.importUpdate(firstImport.manifest().id(), "alice");

        assertThat(update.revisions()).hasSize(2);
        assertThat(update.binding().activeDigest()).isEqualTo(firstDigest);
        assertThat(update.activeRevision().sourceRevision()).isEqualTo("rev-1");
        String secondDigest = update.revisions().stream()
                .filter(revision -> revision.sourceRevision().equals("rev-2"))
                .findFirst().orElseThrow().digest();
        assertThatThrownBy(() -> management.activate(update.manifest().id(), secondDigest, false, "alice"))
                .isInstanceOf(SkillManagementService.SkillException.class);
        management.activate(update.manifest().id(), secondDigest, true, "alice");

        SkillCatalogEntry rolledBack = management.activate(update.manifest().id(), firstDigest, false, "alice");

        assertThat(rolledBack.binding().activeDigest()).isEqualTo(firstDigest);
        assertThat(rolledBack.activeRevision().manifest().license()).isEqualTo("MIT");
        assertThat(management.disable(update.manifest().id(), "alice").binding().state())
                .isEqualTo(SkillBinding.ActivationState.DISABLED);
    }

    @Test
    void should_keep_audit_metadata_only() throws Exception {
        String bodyMarker = "DO_NOT_PERSIST_SKILL_BODY_7249";
        source.publish("rev-audit", "MIT", bodyMarker);

        management.importSkill("modelscope", source.externalId(), "alice");

        String auditText = repository.audit().toString();
        assertThat(auditText).doesNotContain(bodyMarker).doesNotContain("SKILL.md");
        assertThat(repository.audit()).hasSize(2).allSatisfy(event -> {
            assertThat(event.sourceId()).isEqualTo("modelscope");
            assertThat(event.externalId()).isEqualTo(source.externalId());
            assertThat(event.digest()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void should_persist_quarantine_before_validator_runs_and_then_replace_it_with_final_state() throws Exception {
        source.publish("rev-order", "MIT", "ordered validation");
        ArrayList<SkillValidationReport.ValidationState> savedStates = new ArrayList<>();
        SkillCatalogRepository recording = new RecordingRepository(repository, savedStates);
        management = new SkillManagementService(
                new SkillSourceRegistry(List.of(source)), recording,
                new SkillPackageValidator(Set.of(), name -> false),
                new SkillPackageStore(temporary.resolve("data-order")));

        SkillCatalogEntry imported = management.importSkill("modelscope", source.externalId(), "alice");

        assertThat(savedStates).containsExactly(
                SkillValidationReport.ValidationState.QUARANTINED,
                SkillValidationReport.ValidationState.VALID);
        assertThat(imported.revisions()).singleElement().satisfies(revision ->
                assertThat(revision.report().state()).isEqualTo(SkillValidationReport.ValidationState.VALID));
        assertThat(imported.binding().state()).isEqualTo(SkillBinding.ActivationState.DISABLED);
    }

    @Test
    void should_reject_activation_when_an_immutable_package_was_tampered() throws Exception {
        source.publish("rev-tamper", "MIT", "immutable body");
        SkillCatalogEntry imported = management.importSkill("modelscope", source.externalId(), "alice");
        SkillRevision revision = imported.revisions().getFirst();
        Files.writeString(Path.of(revision.packagePath()).resolve("SKILL.md"), "tampered");

        assertThatThrownBy(() -> management.activate(
                imported.manifest().id(), revision.digest(), false, "alice"))
                .isInstanceOf(SkillPackageStore.PackageStoreException.class)
                .extracting(error -> ((SkillPackageStore.PackageStoreException) error).code())
                .isEqualTo("SKILL_DIGEST_MISMATCH");
        assertThat(management.get(imported.manifest().id()).binding().state())
                .isEqualTo(SkillBinding.ActivationState.DISABLED);
    }

    @Test
    void should_keep_a_valid_but_incompatible_revision_disabled() throws Exception {
        Files.createDirectories(temporary.resolve("source"));
        Files.writeString(temporary.resolve("source/SKILL.md"), """
                ---
                name: Shell helper
                license: MIT
                requires:
                  tools: [terminal]
                ---
                Run a terminal command.
                """);
        source.publishSummary("rev-incompatible", "MIT", "Shell helper");

        SkillCatalogEntry imported = management.importSkill(
                "modelscope", source.externalId(), "alice");
        SkillRevision revision = imported.revisions().getFirst();

        assertThat(revision.report().state())
                .isEqualTo(SkillValidationReport.ValidationState.VALID);
        assertThat(revision.report().compatible()).isFalse();
        assertThatThrownBy(() -> management.activate(
                imported.manifest().id(), revision.digest(), false, "alice"))
                .isInstanceOf(SkillManagementService.SkillException.class)
                .extracting(error -> ((SkillManagementService.SkillException) error).code())
                .isEqualTo("SKILL_REVISION_NOT_COMPATIBLE");
        assertThat(management.get(imported.manifest().id()).binding().state())
                .isEqualTo(SkillBinding.ActivationState.DISABLED);
    }

    private record RecordingRepository(
            SkillCatalogRepository delegate,
            List<SkillValidationReport.ValidationState> savedStates) implements SkillCatalogRepository {
        @Override public void saveManifest(SkillManifest manifest) { delegate.saveManifest(manifest); }
        @Override public void saveRevision(SkillRevision revision) {
            savedStates.add(revision.report().state());
            delegate.saveRevision(revision);
        }
        @Override public void saveBinding(SkillBinding binding) { delegate.saveBinding(binding); }
        @Override public java.util.Optional<SkillCatalogEntry> find(String skillId) { return delegate.find(skillId); }
        @Override public java.util.Optional<SkillCatalogEntry> findBySource(String sourceId, String externalId) {
            return delegate.findBySource(sourceId, externalId);
        }
        @Override public List<SkillCatalogEntry> list() { return delegate.list(); }
        @Override public void appendAudit(SkillAuditEvent event) { delegate.appendAudit(event); }
        @Override public List<SkillAuditEvent> audit() { return delegate.audit(); }
    }

    static final class MutableSource implements SkillSourcePlugin {
        private final String sourceId;
        private final Path sourceRoot;
        private final String externalId = "@MiniMax-AI/minimax-pdf";
        private volatile Summary summary;

        MutableSource(String sourceId, Path sourceRoot) {
            this.sourceId = sourceId;
            this.sourceRoot = sourceRoot;
        }

        void publish(String revision, String license, String body) throws Exception {
            publish(revision, license, "Minimax PDF", "Parse PDF documents",
                    List.of("pdf", "document"), body);
        }

        void publish(
                String revision, String license, String name, String description,
                List<String> tags, String body) throws Exception {
            Files.createDirectories(sourceRoot);
            String licenseLine = license == null ? "" : "license: " + license + "\n";
            Files.writeString(sourceRoot.resolve("SKILL.md"),
                    "---\nname: " + name + "\ndescription: " + description + "\n" + licenseLine
                            + "tags: [" + String.join(", ", tags) + "]\n---\n" + body + "\n");
            Files.createDirectories(sourceRoot.resolve("references"));
            Files.writeString(sourceRoot.resolve("references/guide.md"), "Reference " + revision);
            summary = new Summary(externalId, name, description, license,
                    tags, revision, Instant.parse("2026-08-12T00:00:00Z"));
        }

        String externalId() { return externalId; }

        void publishSummary(String revision, String license, String name) {
            summary = new Summary(externalId, name, "Incompatible workflow", license,
                    List.of("terminal"), revision, Instant.parse("2026-08-12T00:00:00Z"));
        }

        @Override public String id() { return sourceId; }
        @Override public SearchPage search(String query, int page, int pageSize) {
            return new SearchPage(List.of(summary), page, pageSize, 1);
        }
        @Override public Detail detail(String ignored) {
            return new Detail(summary, SkillRequirement.none(), Map.of());
        }
        @Override public FetchResult fetch(String ignored) {
            return new FetchResult(externalId, summary.revision(), sourceRoot);
        }
        @Override public UpdateStatus checkUpdate(String ignored, String currentRevision) {
            return new UpdateStatus(!summary.revision().equals(currentRevision), currentRevision, summary.revision());
        }
    }
}
