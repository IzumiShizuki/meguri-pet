package com.meguri.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SkillAvailabilityRegressionTest {
    @TempDir Path temporary;

    @Test void dependencyAddedAfterImportAllowsActivationWithoutReimportOrChangingStoredReport() throws Exception {
        Fixture f = fixture(Set.of(), false);
        var imported = f.management.importSkill("modelscope", f.source.externalId(), "synthetic");
        String id = imported.manifest().id(), digest = imported.revisions().getFirst().digest();
        assertThat(imported.revisions().getFirst().report().compatible()).isFalse();
        f.tools.add("web_fetch");
        assertThat(f.management.get(id).revisions().getFirst().report().compatible()).isTrue();
        assertThat(f.management.activate(id, digest, false, "synthetic").activeRevision()).isNotNull();
        assertThat(f.repository.find(id).orElseThrow().revisions().getFirst().report().compatible()).isFalse();
    }

    @Test void dependencyRemovedAfterImportPreventsActivationAndReadProjectionExplainsIt() throws Exception {
        Fixture f = fixture(Set.of("web_fetch"), false);
        var imported = f.management.importSkill("modelscope", f.source.externalId(), "synthetic");
        String id = imported.manifest().id(), digest = imported.revisions().getFirst().digest();
        f.tools.clear();
        assertThat(f.management.get(id).revisions().getFirst().report().compatible()).isFalse();
        assertThat(f.management.list().getFirst().revisions().getFirst().report().issues())
                .extracting(SkillValidationReport.Issue::code).contains("SKILL_REQUIREMENT_TOOL_MISSING");
        assertThatThrownBy(() -> f.management.activate(id, digest, false, "synthetic"))
                .isInstanceOf(SkillManagementService.SkillException.class);
        assertThat(f.repository.find(id).orElseThrow().revisions().getFirst().report().compatible()).isTrue();
    }

    @Test void environmentPresenceIsRecheckedWithoutDisclosingAValue() throws Exception {
        Fixture f = fixture(Set.of("web_fetch"), true);
        var imported = f.management.importSkill("modelscope", f.source.externalId(), "synthetic");
        f.environment.set(false);
        var report = f.management.get(imported.manifest().id()).revisions().getFirst().report();
        assertThat(report.compatible()).isFalse();
        assertThat(report.issues()).extracting(SkillValidationReport.Issue::code).contains("SKILL_REQUIREMENT_ENV_MISSING");
        assertThat(report.toString()).doesNotContain("SECRET_VALUE");
    }

    @Test void newTurnsRecheckDependenciesWhileEarlierFrozenTurnsRemainUnchanged() throws Exception {
        Fixture f = fixture(Set.of("web_fetch"), false);
        var entry = f.management.importSkill("modelscope", f.source.externalId(), "synthetic");
        f.management.activate(entry.manifest().id(), entry.revisions().getFirst().digest(), false, "synthetic");
        var selection = new SkillSelectionService(f.management);
        var before = selection.freeze("before", "cap-before", "pdf", true, Set.of("web_fetch"));
        assertThat(before.candidates()).singleElement().satisfies(c ->
                assertThat(c.readiness()).isEqualTo(FrozenSkillSnapshot.Readiness.DEPENDENCIES_EXPOSED));
        f.tools.clear();
        var after = selection.freeze("after", "cap-after", "pdf", true, Set.of());
        assertThat(after.candidates()).singleElement().satisfies(c -> {
            assertThat(c.readiness()).isEqualTo(FrozenSkillSnapshot.Readiness.MISSING_DEPENDENCIES);
            assertThat(c.readinessReasons()).contains("SKILL_REQUIREMENT_TOOL_MISSING: missing capability: web_fetch");
        });
        assertThat(before.candidates().getFirst().dependenciesBlocked()).isFalse();
    }

    @Test void globallyPresentToolIsNotClaimedCallableWhenAbsentFromTheFrozenTurn() throws Exception {
        Fixture f = fixture(Set.of("web_fetch"), false);
        var entry = f.management.importSkill("modelscope", f.source.externalId(), "synthetic");
        f.management.activate(entry.manifest().id(), entry.revisions().getFirst().digest(), false, "synthetic");
        var snapshot = new SkillSelectionService(f.management).freeze("restricted", "cap", "pdf", true, Set.of());
        var disclosure = new SkillDisclosureService(f.management);
        disclosure.freeze(snapshot);
        var item = (Map<?, ?>)((List<?>)disclosure.search("restricted", "pdf").get("items")).getFirst();
        assertThat(item.get("readiness")).isEqualTo("NOT_EXPOSED_THIS_TURN");
        assertThat(item.get("readiness_reasons")).isEqualTo(List.of("web_fetch"));
        assertThat(item.get("executable")).isEqualTo(false);
        assertThatThrownBy(() -> disclosure.view("restricted", entry.manifest().id(), "SKILL.md"))
                .isInstanceOf(SkillManagementService.SkillException.class)
                .extracting(e -> ((SkillManagementService.SkillException)e).code()).isEqualTo("SKILL_DEPENDENCIES_UNAVAILABLE");
    }

    @Test void discoveringAnInstalledSkillDoesNotClaimItWasInstalledOrCalledByDiscovery() throws Exception {
        Fixture f = fixture(Set.of("web_fetch"), false);
        f.management.importSkill("modelscope", f.source.externalId(), "synthetic");
        var disclosure = new SkillDisclosureService(f.management);
        disclosure.freeze(new SkillSelectionService(f.management).freeze("discover", "cap", "pdf", true, Set.of()));
        int auditsBefore = f.management.audit().size();
        var discovery = disclosure.discover("discover", "pdf");
        var item = (Map<?, ?>)((List<?>)discovery.get("items")).getFirst();
        assertThat(discovery.get("installation_performed")).isEqualTo(false);
        assertThat(item.get("installed")).isEqualTo(true);
        assertThat(item.get("state")).isEqualTo("DISCOVERED_METADATA");
        assertThat(item.get("callability")).isEqualTo("NOT_ASSESSED");
        assertThat(f.management.audit()).hasSize(auditsBefore);
        assertThat(f.management.list().getFirst().binding().state()).isEqualTo(SkillBinding.ActivationState.DISABLED);
    }

    @Test void blockedMetadataDoesNotStarveAnOtherwiseEligibleSkillOutOfTheCandidateBudget() throws Exception {
        Fixture f = fixture(Set.of("web_fetch"), false);
        for (int i = 0; i < 9; i++) {
            String id = i == 8 ? "z-ready" : "a-blocked-" + i;
            var manifest = new SkillManifest(id, "modelscope", "synthetic/" + id, "PDF", "Read PDF", "MIT",
                    List.of("pdf"), new SkillRequirement(List.of(i == 8 ? "web_fetch" : "missing"), List.of()),
                    false, "revision", java.time.Instant.now());
            String digest = Integer.toHexString(i).repeat(64);
            f.repository.saveManifest(manifest);
            f.repository.saveRevision(new SkillRevision(id, digest, "unused", "revision", manifest,
                    new SkillValidationReport(SkillValidationReport.ValidationState.VALID, true, true, List.of(), null), null));
            f.repository.saveBinding(new SkillBinding(id, digest, SkillBinding.ActivationState.ENABLED, null));
        }
        var snapshot = new SkillSelectionService(f.management).freeze("budget", "cap", "pdf", true, Set.of("web_fetch"));
        assertThat(snapshot.candidates()).hasSize(8);
        assertThat(snapshot.candidates().stream().filter(c -> !c.dependenciesBlocked()))
                .extracting(FrozenSkillSnapshot.Candidate::skillId).containsExactly("z-ready");
    }

    private Fixture fixture(Set<String> initialTools, boolean requiresEnvironment) throws Exception {
        Set<String> tools = new HashSet<>(initialTools);
        AtomicBoolean env = new AtomicBoolean(true);
        var source = new SkillManagementServiceTest.MutableSource("modelscope", temporary.resolve("source"));
        source.publish("revision", "MIT", "Read public documents");
        Path skill = temporary.resolve("source/SKILL.md");
        Files.writeString(skill, Files.readString(skill).replace("\n---\n", "\nrequires:\n  tools: [web_fetch]\n"
                + (requiresEnvironment ? "  env: [SYNTHETIC_SETTING]\n" : "") + "---\n"));
        var repository = new InMemorySkillCatalogRepository();
        var management = new SkillManagementService(new SkillSourceRegistry(List.of(source)), repository,
                new SkillPackageValidator(tools::contains, name -> env.get()), new SkillPackageStore(temporary.resolve("data")));
        return new Fixture(source, repository, management, tools, env);
    }
    private record Fixture(SkillManagementServiceTest.MutableSource source, InMemorySkillCatalogRepository repository,
                           SkillManagementService management, Set<String> tools, AtomicBoolean environment) { }
}
