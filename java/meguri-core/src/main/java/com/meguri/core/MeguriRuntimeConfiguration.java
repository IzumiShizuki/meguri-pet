package com.meguri.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.LlmProviderFactory;
import com.meguri.core.rag.MockRagProvider;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.websearch.DuckDuckGoWebSearchGateway;
import com.meguri.core.websearch.BingRssWebSearchGateway;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.meguri.core.websearch.WebSearchGateway;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

/** Spring wiring that keeps provider selection explicit and offline-first. */
@Configuration
public class MeguriRuntimeConfiguration {
    @Bean
    public LlmProvider meguriLlmProvider(ObjectMapper mapper) {
        return LlmProviderFactory.createFromEnvironment(mapper);
    }

    @Bean
    public RagProvider meguriRagProvider(
            @Value("${meguri.data-root:../../datasets/meguri}") String dataRoot,
            @Value("${meguri.build-id:meguri_local_mock}") String buildId,
            ObjectMapper mapper) {
        Path root = Path.of(dataRoot);
        return new MockRagProvider(root, mapper, resolveBuildId(root, buildId, mapper));
    }

    @Bean
    public WebSearchGateway meguriWebSearchGateway(
            @Value("${meguri.web-search.enabled:false}") boolean enabled,
            @Value("${meguri.web-search.base-url:https://api.duckduckgo.com}") String baseUrl,
            @Value("${meguri.web-search.timeout-ms:8000}") long timeoutMs,
            @Value("${meguri.web-search.max-results:5}") int maxResults,
            ObjectMapper mapper) {
        if (!enabled) return new NoopWebSearchGateway();
        WebClient client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(true)))
                .build();
        Duration timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        if (baseUrl.toLowerCase(java.util.Locale.ROOT).contains("bing.com")) {
            return new BingRssWebSearchGateway(client, mapper, baseUrl, timeout, maxResults);
        }
        return new DuckDuckGoWebSearchGateway(client, mapper, baseUrl, timeout, maxResults);
    }

    private static String resolveBuildId(Path dataRoot, String configured, ObjectMapper mapper) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        try {
            Map<?, ?> report = mapper.readValue(Files.readString(dataRoot.resolve("build_report.json")), Map.class);
            Object value = report.get("build_id");
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        } catch (Exception ignored) {
            // Local mock remains the fail-safe when the canonical build is absent.
        }
        return "meguri_local_mock";
    }
}
