package com.meguri.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelScope public Skill source")
class ModelScopeSkillSourcePluginTest {
    @TempDir Path temporary;

    @Test
    void should_normalize_paginated_public_search_and_filter_private_items() {
        RecordingTransport transport = new RecordingTransport(request -> response(200, """
                {"success":true,"data":{"skills":[
                  {"id":"@MiniMax-AI/minimax-pdf","display_name":"Minimax PDF",
                   "description":"Parse PDFs","license":"Apache-2.0","tags":["pdf"],
                   "file_last_modified":"2026-08-10T12:00:00Z","private":false},
                  {"id":"private/skill","display_name":"Private","private":true}
                ],"page_number":2,"page_size":10,"total":21}}
                """));
        ModelScopeSkillSourcePlugin plugin = plugin(transport);

        SkillSourcePlugin.SearchPage page = plugin.search("pdf 中文", 2, 10);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.items()).singleElement().satisfies(summary -> {
            assertThat(summary.externalId()).isEqualTo("@MiniMax-AI/minimax-pdf");
            assertThat(summary.license()).isEqualTo("Apache-2.0");
            assertThat(summary.revision()).isEqualTo("2026-08-10T12:00:00Z");
        });
        assertThat(transport.requests.getFirst().uri().toString())
                .contains("https://modelscope.cn/openapi/v1/skills?")
                .contains("search=pdf%20%E4%B8%AD%E6%96%87")
                .contains("page_number=2", "page_size=10")
                .doesNotContain("&page=2");
    }

    @Test
    void should_normalize_detail_requirements_and_update_identity() {
        RecordingTransport transport = new RecordingTransport(request -> response(200, """
                {"success":true,"data":{"skill":{
                  "id":"@MiniMax-AI/minimax-pdf","display_name":"Minimax PDF",
                  "description":"Parse PDFs","license":"Apache-2.0","tags":["pdf","document"],
                  "last_modified":"2026-08-11T00:00:00Z","developer":"MiniMax-AI",
                  "category":"document","source_url":"https://modelscope.cn/skills/x","downloads":42,
                  "requires":{"tools":["web_search"],"env":["PDF_TOKEN"]},"private":false
                }}}
                """));
        ModelScopeSkillSourcePlugin plugin = plugin(transport);

        SkillSourcePlugin.Detail detail = plugin.detail("@MiniMax-AI/minimax-pdf");
        SkillSourcePlugin.UpdateStatus update = plugin.checkUpdate(
                "@MiniMax-AI/minimax-pdf", "2026-08-01T00:00:00Z");

        assertThat(detail.requirements().tools()).containsExactly("web_search");
        assertThat(detail.requirements().env()).containsExactly("PDF_TOKEN");
        assertThat(detail.metadata()).containsEntry("developer", "MiniMax-AI")
                .containsEntry("downloads", 42L);
        assertThat(update.updateAvailable()).isTrue();
        assertThat(update.latestRevision()).isEqualTo("2026-08-11T00:00:00Z");
        assertThat(transport.requests).allSatisfy(request -> assertThat(request.uri().toString())
                .contains("/openapi/v1/skills/%40MiniMax-AI/minimax-pdf"));
    }

    @Test
    void should_fetch_only_through_authenticated_loopback_bridge() throws Exception {
        Path staging = temporary.resolve("staging").toAbsolutePath();
        RecordingTransport transport = new RecordingTransport(request -> response(200,
                "{\"staging_path\":\"" + jsonPath(staging) + "\",\"revision\":\"rev-bridge\"," +
                        "\"fetch_id\":\"0123456789abcdef0123456789abcdef\"}"));
        ModelScopeSkillSourcePlugin plugin = plugin(transport);

        SkillSourcePlugin.FetchResult fetched = plugin.fetch("@MiniMax-AI/minimax-pdf");
        plugin.release(fetched);

        HttpRequest request = transport.requests.getFirst();
        assertThat(request.uri()).isEqualTo(URI.create(
                "http://127.0.0.1:8000/internal/skills/modelscope/fetch"));
        assertThat(request.headers().firstValue("X-Meguri-Internal-Token"))
                .contains("bridge-token");
        assertThat(fetched.stagingPath()).isEqualTo(staging);
        assertThat(fetched.revision()).isEqualTo("rev-bridge");
        assertThat(fetched.fetchId()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(transport.requests.get(1).uri()).isEqualTo(URI.create(
                "http://127.0.0.1:8000/internal/skills/modelscope/release"));
    }

    @Test
    void should_map_not_found_server_failure_and_malformed_response_to_stable_errors() {
        assertSourceError(plugin(request -> response(404, "{}")), "MODELSCOPE_SKILL_NOT_FOUND", false);
        assertSourceError(plugin(request -> response(503, "{}")), "MODELSCOPE_SOURCE_UNAVAILABLE", true);
        assertSourceError(plugin(request -> response(200, "not-json")), "MODELSCOPE_SOURCE_UNAVAILABLE", true);
        assertThatThrownBy(() -> plugin(request -> response(200,
                "{\"success\":true,\"data\":{\"skill\":{\"id\":\"\"}}}"))
                .detail("owner/skill"))
                .isInstanceOf(SkillSourcePlugin.SourceException.class)
                .extracting(error -> ((SkillSourcePlugin.SourceException) error).code())
                .isEqualTo("MODELSCOPE_RESPONSE_INVALID");
    }

    @Test
    void should_reject_non_modelscope_source_and_non_loopback_bridge() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        assertThatThrownBy(() -> new ModelScopeSkillSourcePlugin(
                request -> response(200, "{}"), mapper, URI.create("https://example.com"),
                URI.create("http://127.0.0.1:8000"), "token", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://modelscope.cn");
        assertThatThrownBy(() -> new ModelScopeSkillSourcePlugin(
                request -> response(200, "{}"), mapper, URI.create("https://modelscope.cn"),
                URI.create("https://bridge.example.com"), "token", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void should_reject_noncanonical_external_ids_before_transport() {
        RecordingTransport transport = new RecordingTransport(request -> response(200, "{}"));
        ModelScopeSkillSourcePlugin plugin = plugin(transport);

        assertThatThrownBy(() -> plugin.detail("../private"))
                .isInstanceOf(SkillSourcePlugin.SourceException.class)
                .extracting(error -> ((SkillSourcePlugin.SourceException) error).code())
                .isEqualTo("MODELSCOPE_SKILL_ID_INVALID");
        assertThatThrownBy(() -> plugin.fetch("owner/../private"))
                .isInstanceOf(SkillSourcePlugin.SourceException.class)
                .extracting(error -> ((SkillSourcePlugin.SourceException) error).code())
                .isEqualTo("MODELSCOPE_SKILL_ID_INVALID");
        assertThat(transport.requests).isEmpty();
    }

    private ModelScopeSkillSourcePlugin plugin(ModelScopeSkillSourcePlugin.Transport transport) {
        return new ModelScopeSkillSourcePlugin(
                transport, new ObjectMapper().findAndRegisterModules(),
                URI.create("https://modelscope.cn"), URI.create("http://127.0.0.1:8000"),
                "bridge-token", Duration.ofSeconds(2));
    }

    private static void assertSourceError(
            ModelScopeSkillSourcePlugin plugin, String code, boolean retryable) {
        assertThatThrownBy(() -> plugin.search("pdf", 1, 10))
                .isInstanceOf(SkillSourcePlugin.SourceException.class)
                .satisfies(error -> {
                    SkillSourcePlugin.SourceException source = (SkillSourcePlugin.SourceException) error;
                    assertThat(source.code()).isEqualTo(code);
                    assertThat(source.retryable()).isEqualTo(retryable);
                });
    }

    private static ModelScopeSkillSourcePlugin.TransportResponse response(int status, String body) {
        return new ModelScopeSkillSourcePlugin.TransportResponse(status, body);
    }

    private static String jsonPath(Path value) {
        return value.toString().replace("\\", "\\\\");
    }

    private static final class RecordingTransport implements ModelScopeSkillSourcePlugin.Transport {
        private final java.util.function.Function<HttpRequest, ModelScopeSkillSourcePlugin.TransportResponse> handler;
        private final List<HttpRequest> requests = new ArrayList<>();

        private RecordingTransport(
                java.util.function.Function<HttpRequest, ModelScopeSkillSourcePlugin.TransportResponse> handler) {
            this.handler = handler;
        }

        @Override public ModelScopeSkillSourcePlugin.TransportResponse send(HttpRequest request) {
            requests.add(request);
            return handler.apply(request);
        }
    }
}
