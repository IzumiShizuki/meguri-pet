package com.meguri.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresRetrievalTraceRepositoryTest {
    private static final String QUERY_SECRET = "private user query token-917";
    private static final String MEMORY_SECRET = "memory body secret-token-381";
    private static final String WEB_SECRET = "web body secret-token-592";
    private static final String CITATION_SECRET = "private citation title token-714";
    private static final String URI_QUERY_SECRET = "private-uri-token-833";
    private static final String DECISION_SECRET = "provider response body token-491";
    private static final String DEGRADATION_SECRET = "exception response body token-265";

    @Test void safeProjectionRetainsReplayMetadataWithoutSensitiveText() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RetrievalTraceProjection projection =
                RetrievalTraceProjection.capture(trace());

        String json = mapper.writeValueAsString(projection);

        assertThat(json).doesNotContain(QUERY_SECRET, MEMORY_SECRET, WEB_SECRET,
                        CITATION_SECRET, URI_QUERY_SECRET, DECISION_SECRET,
                        DEGRADATION_SECRET,
                        "\"content\":", "\"originalQuery\":", "\"rewrittenQuery\":")
                .contains("\"projectionVersion\":\"safe-retrieval-trace-v1\"")
                .contains("\"contentDigest\":")
                .contains("memory-1", "web-1", "documentVersionId", "chunk-1",
                        "\"status\":\"DEGRADED\"");

        RetrievalTrace redacted = mapper.readValue(json,
                RetrievalTraceProjection.class).toRedactedTrace();
        assertThat(redacted.items()).extracting(RetrievalItem::sourceId)
                .containsExactly("memory-1");
        assertThat(redacted.items().getFirst().content()).startsWith("sha256:");
        assertThat(redacted.lanes().get(SourceType.WEB).items())
                .extracting(RetrievalItem::sourceId).containsExactly("web-1");
        assertThat(redacted.lanes().get(SourceType.WEB).status())
                .isEqualTo(RetrievalLaneResult.Status.DEGRADED);
    }

    @Test void postgresSaveSerializesOnlyTheSafeProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgresRetrievalTraceRepository repository =
                new PostgresRetrievalTraceRepository(jdbc,
                        new ObjectMapper().findAndRegisterModules(), false);

        repository.save(trace());

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), values.capture());
        String json = (String) values.getValue()[7];
        assertThat(json).doesNotContain(QUERY_SECRET, MEMORY_SECRET, WEB_SECRET,
                        CITATION_SECRET, URI_QUERY_SECRET, DECISION_SECRET,
                        DEGRADATION_SECRET)
                .contains("safe-retrieval-trace-v1", "contentDigest");
    }

    private static RetrievalTrace trace() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        RetrievalPlan plan = new RetrievalPlan(RetrievalMode.FAST,
                QueryRewriteResult.unchanged(QUERY_SECRET),
                Set.of(SourceType.MEMORY, SourceType.WEB),
                Map.of(SourceType.MEMORY, 4, SourceType.WEB, 4),
                Map.of(SourceType.MEMORY, 1, SourceType.WEB, 1),
                false, 1, 2, now.plusSeconds(1));
        RetrievalItem memory = item(SourceType.MEMORY, "memory-1", MEMORY_SECRET,
                now, RetrievalCitation.single(
                        "memory://1?access_token=" + URI_QUERY_SECRET,
                        CITATION_SECRET, "v3",
                        "chunk-1", 0, 10));
        RetrievalItem web = item(SourceType.WEB, "web-1", WEB_SECRET, now,
                RetrievalCitation.external("https://example.test/source"));
        RetrievalBundle bundle = new RetrievalBundle("trace-safe", plan, now,
                List.of(memory), Map.of(
                        SourceType.MEMORY, RetrievalLaneResult.success(
                                SourceType.MEMORY, "memory", List.of(memory)),
                        SourceType.WEB, RetrievalLaneResult.degraded(
                                SourceType.WEB, "web", List.of(web),
                                DEGRADATION_SECRET)),
                List.of(DEGRADATION_SECRET));
        RetrievalTrace captured = RetrievalTrace.capture(bundle,
                new RetrievalContext("user", Set.of(), "snapshot-7", 7, now,
                        now.plusSeconds(1), "trace-safe"));
        RetrievalCandidateTrace candidate = captured.candidates().getFirst();
        return new RetrievalTrace(captured.traceId(), captured.plan(),
                captured.snapshotId(), captured.revision(), captured.validAt(),
                captured.algorithmRevision(), captured.completedAt(),
                captured.items(), captured.lanes(), captured.ranks(),
                List.of(new RetrievalCandidateTrace(candidate.sourceType(),
                        candidate.sourceId(), candidate.sourceRank(),
                        candidate.rankTrace(), candidate.citation(),
                        candidate.selected(), DECISION_SECRET)),
                captured.degradations());
    }

    private static RetrievalItem item(SourceType source, String id, String content,
                                      Instant now, RetrievalCitation citation) {
        return new RetrievalItem(source, id, content, citation, .8,
                new RankTrace(Map.of(RankSignal.STRUCTURED, 1), .5), 10, now,
                null, List.of("chunk-1"), List.of(), "trace-safe");
    }
}
