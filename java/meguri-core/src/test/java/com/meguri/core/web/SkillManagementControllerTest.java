package com.meguri.core.web;

import com.meguri.core.security.CoreIdentityVerifier;
import com.meguri.core.skill.SkillBinding;
import com.meguri.core.skill.SkillCatalogEntry;
import com.meguri.core.skill.SkillManagementService;
import com.meguri.core.skill.SkillManifest;
import com.meguri.core.skill.SkillRequirement;
import com.meguri.core.skill.SkillRevision;
import com.meguri.core.skill.SkillSourcePlugin;
import com.meguri.core.skill.SkillValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Scoped Skill management API")
class SkillManagementControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void should_serialize_safe_snake_case_projection_without_package_path() {
        SkillManagementService skills = mock(SkillManagementService.class);
        SkillCatalogEntry entry = entry();
        when(skills.list()).thenReturn(List.of(entry));
        WebTestClient client = WebTestClient.bindToController(new SkillManagementController(
                skills, new CoreIdentityVerifier(false, "tenant", "", ""))).build();

        client.get().uri("/internal/v1/skills")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].manifest.source_id").isEqualTo("modelscope")
                .jsonPath("$[0].manifest.external_id").isEqualTo("@MiniMax-AI/minimax-pdf")
                .jsonPath("$[0].manifest.trust").isEqualTo("UNTRUSTED_EXTERNAL")
                .jsonPath("$[0].revisions[0].source_revision").isEqualTo("rev-1")
                .jsonPath("$[0].revisions[0].report.redistributable").isEqualTo(true)
                .jsonPath("$[0].revisions[0].package_path").doesNotExist()
                .jsonPath("$[0].binding.active_digest").isEqualTo("a".repeat(64));
    }

    @Test
    void should_require_manage_scope_for_mutation_while_allowing_read_scope() {
        SkillManagementService skills = mock(SkillManagementService.class);
        when(skills.list()).thenReturn(List.of());
        CoreIdentityVerifier identity = new CoreIdentityVerifier(
                true, "tenant-hosted", "", "shared-secret",
                "", "", "skill:read");
        WebTestClient client = WebTestClient.bindToController(
                new SkillManagementController(skills, identity)).build();

        client.get().uri("/internal/v1/skills")
                .headers(SkillManagementControllerTest::identityHeaders)
                .exchange()
                .expectStatus().isOk();
        client.post().uri("/internal/v1/skills/imports")
                .headers(SkillManagementControllerTest::identityHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"source_id\":\"modelscope\",\"external_id\":\"@MiniMax-AI/minimax-pdf\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void should_serialize_source_search_detail_and_update_contracts_as_snake_case() {
        SkillManagementService skills = mock(SkillManagementService.class);
        SkillSourcePlugin.Summary summary = new SkillSourcePlugin.Summary(
                "@MiniMax-AI/minimax-pdf", "Minimax PDF", "Parse PDFs", "MIT",
                List.of("pdf"), "rev-2", NOW);
        when(skills.search("modelscope", "pdf", 2, 10)).thenReturn(
                new SkillSourcePlugin.SearchPage(List.of(summary), 2, 10, 21));
        when(skills.detail("modelscope", summary.externalId())).thenReturn(
                new SkillSourcePlugin.Detail(summary,
                        new SkillRequirement(List.of("web_search"), List.of("PDF_TOKEN")),
                        Map.of("developer", "MiniMax-AI")));
        when(skills.checkUpdate(entry().manifest().id(), "local-user")).thenReturn(
                new SkillSourcePlugin.UpdateStatus(true, "rev-1", "rev-2"));
        WebTestClient client = WebTestClient.bindToController(new SkillManagementController(
                skills, new CoreIdentityVerifier(false, "tenant", "", ""))).build();

        client.get().uri("/internal/v1/skill-sources/modelscope/skills?q=pdf&page=2&page_size=10")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.page_size").isEqualTo(10)
                .jsonPath("$.items[0].external_id").isEqualTo(summary.externalId())
                .jsonPath("$.items[0].updated_at").isEqualTo(NOW.toString());
        client.get().uri(uri -> uri.path("/internal/v1/skill-sources/modelscope/skills/detail")
                        .queryParam("skill_id", summary.externalId()).build())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.summary.external_id").isEqualTo(summary.externalId())
                .jsonPath("$.requirements.tools[0]").isEqualTo("web_search");
        client.post().uri("/internal/v1/skills/{id}:check-update", entry().manifest().id())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.update_available").isEqualTo(true)
                .jsonPath("$.latest_revision").isEqualTo("rev-2");
    }

    @Test
    void should_route_activation_disable_and_update_to_management_service() {
        SkillManagementService skills = mock(SkillManagementService.class);
        SkillCatalogEntry entry = entry();
        String skillId = entry.manifest().id();
        String digest = entry.revisions().getFirst().digest();
        when(skills.activate(skillId, digest, true, "local-user")).thenReturn(entry);
        when(skills.disable(skillId, "local-user")).thenReturn(entry);
        when(skills.importUpdate(skillId, "local-user")).thenReturn(entry);
        WebTestClient client = WebTestClient.bindToController(new SkillManagementController(
                skills, new CoreIdentityVerifier(false, "tenant", "", ""))).build();

        client.post().uri("/internal/v1/skills/{id}/revisions/{digest}:activate", skillId, digest)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"license_confirmed\":true}")
                .exchange().expectStatus().isOk();
        client.post().uri("/internal/v1/skills/{id}:disable", skillId)
                .exchange().expectStatus().isOk();
        client.post().uri("/internal/v1/skills/{id}:import-update", skillId)
                .exchange().expectStatus().isOk();

        verify(skills).activate(skillId, digest, true, "local-user");
        verify(skills).disable(skillId, "local-user");
        verify(skills).importUpdate(skillId, "local-user");
    }

    private static SkillCatalogEntry entry() {
        String skillId = "skill_1234567890abcdef12345678";
        String digest = "a".repeat(64);
        SkillManifest manifest = new SkillManifest(
                skillId, "modelscope", "@MiniMax-AI/minimax-pdf", "Minimax PDF",
                "Parse PDFs", "Apache-2.0", List.of("pdf"), SkillRequirement.none(),
                false, "rev-1", NOW);
        SkillValidationReport report = new SkillValidationReport(
                SkillValidationReport.ValidationState.VALID, true, true, List.of(), NOW);
        SkillRevision revision = new SkillRevision(
                skillId, digest, "D:\\private\\meguri\\skills\\" + digest,
                "rev-1", manifest, report, NOW);
        return new SkillCatalogEntry(manifest, List.of(revision), new SkillBinding(
                skillId, digest, SkillBinding.ActivationState.ENABLED, NOW));
    }

    private static void identityHeaders(HttpHeaders headers) {
        headers.setBearerAuth("shared-secret");
        headers.set("X-Meguri-Tenant-ID", "tenant-hosted");
        headers.set("X-Meguri-User-ID", "user-a");
        headers.set("X-Meguri-Client-ID", "desktop_pet");
        headers.set("X-Meguri-Session-ID", "session-a");
    }
}
