package com.meguri.core.knowledge.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.knowledge.InMemoryKnowledgeRepository;
import com.meguri.core.knowledge.IngestionOutcome;
import com.meguri.core.knowledge.DeterministicKnowledgeProjector;
import com.meguri.core.knowledge.KnowledgeEvidenceValidator;
import com.meguri.core.knowledge.KnowledgeIngestionService;
import com.meguri.core.knowledge.NotionHttpResponse;
import com.meguri.core.knowledge.SecretDetector;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotionSyncCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void missingTokenFailsClosedWithoutCallingNetwork() {
        NotionKnowledgeProperties properties = enabledProperties();
        AtomicInteger calls = new AtomicInteger();
        NotionSyncCoordinator coordinator = coordinator(
                properties, request -> {
                    calls.incrementAndGet();
                    throw new AssertionError("network must not be called");
                }, new InMemoryKnowledgeRepository());

        assertThatThrownBy(coordinator::synchronizeNow)
                .isInstanceOf(NotionSyncUnavailableException.class)
                .hasMessage("Notion knowledge token is not configured");
        assertThat(calls).hasValue(0);
    }

    @Test
    void synchronizesAllowlistedPageThroughAtomicIngestionService() {
        NotionKnowledgeProperties properties = enabledProperties();
        properties.setToken("ntn_test_secret");
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSyncCoordinator coordinator = coordinator(properties, request -> {
            if (request.path().equals("/v1/pages/page-1")) {
                return new NotionHttpResponse(200, """
                        {
                          "id":"page-1",
                          "last_edited_time":"2026-07-28T11:00:00Z",
                          "archived":false,
                          "in_trash":false,
                          "properties":{
                            "Name":{"type":"title","title":[{"plain_text":"Meguri KB"}]}
                          }
                        }
                        """);
            }
            return new NotionHttpResponse(200, """
                    {
                      "results":[{
                        "id":"block-1",
                        "type":"paragraph",
                        "has_children":false,
                        "paragraph":{"rich_text":[{"plain_text":"Evidence from Notion"}]}
                      }],
                      "has_more":false,
                      "next_cursor":null
                    }
                    """);
        }, repository);

        NotionSyncReport report = coordinator.synchronizeNow();

        assertThat(report.pages()).isEqualTo(1);
        assertThat(report.outcomes()).containsEntry(IngestionOutcome.PUBLISHED, 1L);
        var document = repository.documents().getFirst();
        var activeVersion = repository.findActiveVersion(document.id()).orElseThrow();
        assertThat(activeVersion.id()).isNotBlank();
        assertThat(repository.chunks(activeVersion.id()))
                .anySatisfy(chunk -> assertThat(chunk.content()).contains("Evidence from Notion"));
    }

    @Test
    void unchangedPageReusesSnapshotButTimestampAndAclChangesRebuildBlocks() {
        NotionKnowledgeProperties properties = enabledProperties();
        properties.setToken("ntn_test_secret");
        AtomicInteger pageCalls = new AtomicInteger();
        AtomicInteger blockCalls = new AtomicInteger();
        NotionSyncCoordinator coordinator = coordinator(properties, request -> {
            if (request.path().startsWith("/v1/pages/")) {
                int call = pageCalls.incrementAndGet();
                String edited = call < 3
                        ? "2026-07-28T11:00:00Z"
                        : "2026-07-28T11:30:00Z";
                String title = call < 6 ? "Page" : "Renamed Page";
                return new NotionHttpResponse(200, """
                        {"id":"page-1","last_edited_time":"%s",
                         "archived":false,"in_trash":false,
                         "properties":{"Name":{"type":"title","title":[{"plain_text":"%s"}]}}}
                        """.formatted(edited, title));
            }
            blockCalls.incrementAndGet();
            return new NotionHttpResponse(200, """
                    {"results":[{"id":"block-1","type":"paragraph","has_children":false,
                     "paragraph":{"rich_text":[{"plain_text":"Stable evidence"}]}}],
                     "has_more":false,"next_cursor":null}
                    """);
        }, new InMemoryKnowledgeRepository());

        coordinator.synchronizeNow();
        coordinator.synchronizeNow();
        coordinator.synchronizeNow();
        properties.setPrincipals(Set.of("user:izumi", "group:trusted"));
        coordinator.synchronizeNow();
        properties.setToken("ntn_rotated_secret");
        coordinator.synchronizeNow();
        coordinator.synchronizeNow();

        assertThat(pageCalls).hasValue(6);
        assertThat(blockCalls).hasValue(5);
    }

    @Test
    void disabledScheduledRunIsANoOp() {
        NotionKnowledgeProperties properties = enabledProperties();
        properties.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        NotionSyncCoordinator coordinator = coordinator(
                properties, request -> {
                    calls.incrementAndGet();
                    return new NotionHttpResponse(500, "");
                }, new InMemoryKnowledgeRepository());

        coordinator.scheduledSynchronize();

        assertThat(calls).hasValue(0);
    }

    private static NotionKnowledgeProperties enabledProperties() {
        NotionKnowledgeProperties properties = new NotionKnowledgeProperties();
        properties.setEnabled(true);
        properties.setAllowlist(Set.of("page-1"));
        properties.setTenantId("meguri");
        properties.setPrincipals(Set.of("user:izumi"));
        return properties;
    }

    private static NotionSyncCoordinator coordinator(
            NotionKnowledgeProperties properties,
            com.meguri.core.knowledge.NotionHttpPort http,
            InMemoryKnowledgeRepository repository) {
        ConfiguredNotionSourceRegistry source = new ConfiguredNotionSourceRegistry(
                properties, http, new ObjectMapper());
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                repository, List.of(source), new DeterministicKnowledgeProjector(),
                new KnowledgeEvidenceValidator(), new SecretDetector(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new NotionSyncCoordinator(
                properties, source, () -> service,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
