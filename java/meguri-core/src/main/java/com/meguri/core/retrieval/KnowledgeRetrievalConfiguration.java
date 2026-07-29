package com.meguri.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.knowledge.DeterministicSearchProjector;
import com.meguri.core.knowledge.DeterministicKnowledgeProjector;
import com.meguri.core.knowledge.InMemoryKnowledgeRepository;
import com.meguri.core.knowledge.KnowledgeEvidenceValidator;
import com.meguri.core.knowledge.KnowledgeIngestionService;
import com.meguri.core.knowledge.KnowledgeRepository;
import com.meguri.core.knowledge.PostgresKnowledgeRepository;
import com.meguri.core.knowledge.SecretDetector;
import com.meguri.core.knowledge.SourceRegistry;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.websearch.HttpWebContentExtractor;
import com.meguri.core.websearch.WebRetrievalProvider;
import com.meguri.core.websearch.WebSearchGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Offline-first knowledge authority and typed retrieval wiring. */
@Configuration
public class KnowledgeRetrievalConfiguration {
    @Bean
    @ConditionalOnMissingBean(KnowledgeRepository.class)
    @ConditionalOnProperty(
            name = "meguri.knowledge.repository-mode",
            havingValue = "in-memory",
            matchIfMissing = true)
    public KnowledgeRepository meguriInMemoryKnowledgeRepository() {
        return new InMemoryKnowledgeRepository();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeRepository.class)
    @ConditionalOnProperty(
            name = "meguri.knowledge.repository-mode",
            havingValue = "postgres")
    public KnowledgeRepository meguriPostgresKnowledgeRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            TransactionTemplate transactions) {
        return new PostgresKnowledgeRepository(jdbc, mapper, transactions);
    }

    @Bean
    public KnowledgeSearchProjectionPort meguriKnowledgeSearchProjection(
            KnowledgeRepository repository) {
        return new KnowledgeRepositoryProjectionAdapter(repository);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeIngestionService.class)
    public KnowledgeIngestionService meguriKnowledgeIngestionService(
            KnowledgeRepository repository,
            List<SourceRegistry> sources) {
        return new KnowledgeIngestionService(
                repository,
                sources,
                new DeterministicKnowledgeProjector(),
                new KnowledgeEvidenceValidator(),
                new SecretDetector(),
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(
            name = "meguri.knowledge.repository-mode",
            havingValue = "in-memory",
            matchIfMissing = true)
    public RetrievalTraceRepository meguriRetrievalTraceRepository() {
        return new InMemoryRetrievalTraceRepository();
    }

    @Bean
    @ConditionalOnProperty(
            name = "meguri.knowledge.repository-mode",
            havingValue = "postgres")
    public RetrievalTraceRepository meguriPostgresRetrievalTraceRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        return new PostgresRetrievalTraceRepository(jdbc, mapper);
    }

    @Bean(destroyMethod = "close")
    public KnowledgeTurnRetrievalRuntime meguriKnowledgeTurnRetrievalRuntime(
            KnowledgeSearchProjectionPort projection,
            RetrievalTraceRepository traces,
            @Value("${meguri.knowledge.retrieval-timeout-ms:2000}") long retrievalTimeoutMs,
            @Value("${meguri.knowledge.graph-timeout-ms:500}") long graphTimeoutMs,
            @Value("${meguri.knowledge.parent-context-token-limit:512}")
            int parentContextTokenLimit) {
        KnowledgeVectorizer vectorizer = text ->
                DeterministicSearchProjector.embedding(text).stream()
                        .mapToDouble(Double::doubleValue)
                        .toArray();
        KnowledgeRepositoryRetrievalBridge bridge =
                new KnowledgeRepositoryRetrievalBridge(
                        projection, vectorizer, new WeightedRrfFusion(),
                        parentContextTokenLimit);
        RetrievalPlanner knowledgeOnlyPlanner = (query, mode, deadline) -> {
            RetrievalPlan planned =
                    new DefaultRetrievalPlanner().plan(query, mode, deadline);
            if (planned.mode() == RetrievalMode.NONE) return planned;
            return new RetrievalPlan(
                    planned.mode(),
                    planned.query(),
                    Set.of(SourceType.KNOWLEDGE),
                    Map.of(SourceType.KNOWLEDGE,
                            planned.sourceBudgets().get(SourceType.KNOWLEDGE)),
                    Map.of(SourceType.KNOWLEDGE, 1),
                    planned.graphEnabled(),
                    planned.graphMaxHops(),
                    planned.totalItemLimit(),
                    planned.deadline());
        };
        KnowledgeGraphRetrievalService graph =
                new KnowledgeGraphRetrievalService(bridge, bridge, bridge);
        RetrievalRuntime runtime = new RetrievalRuntime(
                knowledgeOnlyPlanner,
                Map.of(),
                bridge,
                graph,
                traces,
                Duration.ofMillis(Math.max(100, retrievalTimeoutMs)),
                Duration.ofMillis(Math.max(50, graphTimeoutMs)));
        return new KnowledgeTurnRetrievalRuntime(bridge, runtime);
    }

    @Bean(destroyMethod = "close")
    public UnifiedRetrievalFacade meguriUnifiedRetrievalFacade(
            KnowledgeSearchProjectionPort projection,
            RetrievalTraceRepository traces,
            RagProvider lore,
            ObjectProvider<MemoryGateway> memoryProvider,
            WebSearchGateway web,
            @Value("${meguri.knowledge.retrieval-timeout-ms:2000}") long retrievalTimeoutMs,
            @Value("${meguri.knowledge.graph-timeout-ms:500}") long graphTimeoutMs,
            @Value("${meguri.knowledge.parent-context-token-limit:512}") int parentContextTokenLimit,
            @Value("${meguri.web-search.allowlist:}") String webAllowlist,
            @Value("${meguri.web-search.max-age-days:3650}") long webMaxAgeDays) {
        KnowledgeVectorizer vectorizer = text ->
                DeterministicSearchProjector.embedding(text).stream()
                        .mapToDouble(Double::doubleValue).toArray();
        KnowledgeRepositoryRetrievalBridge bridge = new KnowledgeRepositoryRetrievalBridge(
                projection, vectorizer, new WeightedRrfFusion(), parentContextTokenLimit);
        java.util.EnumMap<SourceType, RetrievalProvider> providers =
                new java.util.EnumMap<>(SourceType.class);
        providers.put(SourceType.LORE, new LoreRetrievalProviderAdapter(lore));
        MemoryGateway memory = memoryProvider.getIfAvailable();
        if (memory != null) {
            providers.put(SourceType.MEMORY, new MemoryRetrievalProviderAdapter(memory));
        }
        Set<String> allowlist = Arrays.stream(webAllowlist == null ? new String[0] : webAllowlist.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        providers.put(SourceType.WEB, new WebRetrievalProvider(
                web, new HttpWebContentExtractor(WebClient.builder().build()), allowlist,
                Duration.ofDays(Math.max(1, webMaxAgeDays))));
        KnowledgeGraphRetrievalService graph =
                new KnowledgeGraphRetrievalService(bridge, bridge, bridge);
        RetrievalRuntime runtime = new RetrievalRuntime(
                new DefaultRetrievalPlanner(), new RetrievalGate(), providers, bridge, graph,
                new BundleAssembler(), traces,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofMillis(Math.max(100, retrievalTimeoutMs)),
                Duration.ofMillis(Math.max(50, graphTimeoutMs)), true,
                RetrievalAuthorizationPolicy.scopeBased());
        return new UnifiedRetrievalFacade(bridge, runtime);
    }
}
