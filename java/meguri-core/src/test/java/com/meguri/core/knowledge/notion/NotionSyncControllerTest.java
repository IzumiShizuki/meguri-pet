package com.meguri.core.knowledge.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.knowledge.DeterministicKnowledgeProjector;
import com.meguri.core.knowledge.KnowledgeEvidenceValidator;
import com.meguri.core.knowledge.KnowledgeIngestionService;
import com.meguri.core.knowledge.InMemoryKnowledgeRepository;
import com.meguri.core.knowledge.SecretDetector;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.util.List;

class NotionSyncControllerTest {

    @Test
    void manualSyncReturnsStableUnavailableResponseWithoutToken() {
        NotionKnowledgeProperties properties = new NotionKnowledgeProperties();
        properties.setEnabled(true);
        properties.setManualSyncEnabled(true);
        properties.setManagementToken("management-secret");
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        ConfiguredNotionSourceRegistry source = new ConfiguredNotionSourceRegistry(
                properties, request -> {
                    throw new AssertionError("network must not be called");
                }, new ObjectMapper());
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(
                repository, List.of(source), new DeterministicKnowledgeProjector(),
                new KnowledgeEvidenceValidator(), new SecretDetector(), Clock.systemUTC());
        NotionSyncCoordinator coordinator = new NotionSyncCoordinator(
                properties, source, () -> ingestion, Clock.systemUTC());
        WebTestClient client = WebTestClient.bindToController(
                        new NotionSyncController(coordinator, properties))
                .build();

        client.post()
                .uri("/internal/v1/knowledge/notion:sync")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCESS_DENIED");

        client.post()
                .uri("/internal/v1/knowledge/notion:sync")
                .header("X-Meguri-Admin-Token", "management-secret")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOTION_SYNC_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Notion knowledge allowlist is empty");
    }
}
