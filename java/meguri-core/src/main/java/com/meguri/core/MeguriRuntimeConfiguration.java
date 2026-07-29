package com.meguri.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.adapter.application.ClientBindingRepository;
import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.adapter.infrastructure.InMemoryClientBindingRepository;
import com.meguri.core.adapter.infrastructure.JdbcClientBindingRepository;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.DefaultCapabilityPolicy;
import com.meguri.core.capability.DefaultResultNormalizer;
import com.meguri.core.capability.InMemoryMcpSourceStore;
import com.meguri.core.capability.JdbcCapabilityRuntimeStore;
import com.meguri.core.capability.JdbcMcpSourceStore;
import com.meguri.core.capability.McpSourceStore;
import com.meguri.core.capability.PersistingCapabilityCatalog;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.LlmProviderFactory;
import com.meguri.core.llm.ProviderTokenizer;
import com.meguri.core.context.CompanionContextRuntime;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.context.ContextProfile;
import com.meguri.core.context.ContextRuntimePersistence;
import com.meguri.core.context.InMemoryContextRuntimePersistence;
import com.meguri.core.metrics.PromptCacheMetricsService;
import com.meguri.core.rag.MockRagProvider;
import com.meguri.core.rag.PythonRagGateway;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.runtime.InMemoryTurnJournal;
import com.meguri.core.runtime.PostgresTurnJournal;
import com.meguri.core.runtime.NoopSessionContextPersistence;
import com.meguri.core.runtime.PostgresSessionContextPersistence;
import com.meguri.core.runtime.SessionContextPersistence;
import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.runtime.TurnJournal;
import com.meguri.core.websearch.DuckDuckGoWebSearchGateway;
import com.meguri.core.websearch.BingRssWebSearchGateway;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.weather.OpenMeteoWeatherGateway;
import com.meguri.core.weather.WeatherGateway;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import javax.sql.DataSource;

/** Spring wiring that keeps provider selection explicit and offline-first. */
@Configuration
public class MeguriRuntimeConfiguration {
    @Bean
    public LlmProvider meguriLlmProvider(ObjectMapper mapper, PromptCacheMetricsService metrics) {
        return LlmProviderFactory.createFromEnvironment(mapper, metrics);
    }

    @Bean
    public ProviderTokenizer meguriProviderTokenizer(LlmProvider provider) {
        return provider.tokenizer();
    }

    @Bean
    public ContextProfile meguriContextProfile(
            LlmProvider provider,
            @Value("${meguri.context.max-input-tokens:16000}") int maxInputTokens,
            @Value("${meguri.context.reserved-output-tokens:1600}") int reservedOutputTokens,
            @Value("${meguri.context.protocol-overhead-tokens:700}") int protocolOverheadTokens) {
        java.util.EnumMap<ContextBundle.BlockType, ContextProfile.SourceBudget> budgets =
                new java.util.EnumMap<>(ContextBundle.BlockType.class);
        budgets.put(ContextBundle.BlockType.RECENT_RAW, new ContextProfile.SourceBudget(1200, 7000));
        budgets.put(ContextBundle.BlockType.SUMMARY, new ContextProfile.SourceBudget(256, 2500));
        budgets.put(ContextBundle.BlockType.REHYDRATED, new ContextProfile.SourceBudget(256, 2000));
        budgets.put(ContextBundle.BlockType.MEMORY, new ContextProfile.SourceBudget(256, 2000));
        budgets.put(ContextBundle.BlockType.RETRIEVAL, new ContextProfile.SourceBudget(512, 3500));
        budgets.put(ContextBundle.BlockType.TOOL_RESULT, new ContextProfile.SourceBudget(0, 1500));
        return new ContextProfile(provider.modelId(), maxInputTokens, reservedOutputTokens,
                protocolOverheadTokens, 0.70, 0.90, budgets);
    }

    @Bean
    public TurnJournal meguriTurnJournal(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectProvider<TransactionTemplate> transactionProvider,
            ObjectMapper mapper,
            @Value("${meguri.turn-journal.mode:auto}") String configuredMode,
            @Value("${meguri.build-id:meguri_local_mock}") String buildId) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        String mode = configuredMode == null ? "auto"
                : configuredMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (dataSource == null) {
            if ("postgres".equals(mode)) {
                throw new IllegalStateException("PostgreSQL Turn journal requires a DataSource");
            }
            if (!"auto".equals(mode) && !"in-memory".equals(mode)) {
                throw new IllegalArgumentException("unsupported Turn journal mode: " + mode);
            }
            return new InMemoryTurnJournal(mapper);
        }
        if ("in-memory".equals(mode)) {
            throw new IllegalStateException(
                    "in-memory Turn journal is forbidden when a DataSource is available");
        }
        if (!"auto".equals(mode) && !"postgres".equals(mode)) {
            throw new IllegalArgumentException("unsupported Turn journal mode: " + mode);
        }
        JdbcTemplate jdbcTemplate = jdbcProvider.getIfAvailable();
        TransactionTemplate transactions = transactionProvider.getIfAvailable();
        if (jdbcTemplate == null || transactions == null) {
            throw new IllegalStateException("PostgreSQL Turn journal requires JDBC transactions");
        }
        String recoveryBuildId = buildId == null || buildId.isBlank() ? "meguri_local_mock" : buildId.trim();
        return new PostgresTurnJournal(jdbcTemplate, mapper, transactions, recoveryBuildId);
    }

    @Bean
    public SessionContextPersistence meguriSessionContextPersistence(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectMapper mapper,
            @Value("${meguri.turn-journal.mode:auto}") String configuredMode) {
        String mode = configuredMode == null
                ? "auto" : configuredMode.trim().toLowerCase(java.util.Locale.ROOT);
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if ("in-memory".equals(mode) || "auto".equals(mode) && dataSource == null) {
            return new NoopSessionContextPersistence();
        }
        if (!"auto".equals(mode) && !"postgres".equals(mode)) {
            throw new IllegalArgumentException("unsupported Session Context mode: " + mode);
        }
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (dataSource == null || jdbc == null) {
            throw new IllegalStateException("PostgreSQL Session Context requires DataSource and JdbcTemplate");
        }
        return new PostgresSessionContextPersistence(jdbc, mapper);
    }

    @Bean
    public ContextRuntimePersistence meguriContextRuntimePersistence(
            SessionContextPersistence sessionPersistence) {
        return sessionPersistence instanceof ContextRuntimePersistence durable
                ? durable : new InMemoryContextRuntimePersistence();
    }

    @Bean
    public SessionContextStore meguriSessionContextStore(
            SessionContextPersistence persistence) {
        return new SessionContextStore(20, persistence);
    }

    @Bean
    public CompanionContextRuntime meguriCompanionContextRuntime(
            SessionContextStore sessions,
            ContextRuntimePersistence persistence,
            ProviderTokenizer tokenizer) {
        return new CompanionContextRuntime(sessions, persistence, tokenizer);
    }

    @Bean
    @ConditionalOnProperty(name = "meguri.turn-journal.mode", havingValue = "in-memory", matchIfMissing = true)
    public ClientBindingRepository meguriClientBindingRepository() {
        return new InMemoryClientBindingRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "meguri.turn-journal.mode", havingValue = "postgres")
    public ClientBindingRepository meguriPostgresClientBindingRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        return new JdbcClientBindingRepository(jdbcTemplate, mapper);
    }

    @Bean
    public ClientHandshakeService meguriClientHandshakeService(
            ClientBindingRepository repository) {
        return new ClientHandshakeService(repository);
    }

    @Bean(destroyMethod = "close")
    public CapabilityRuntimeFacade meguriCapabilityRuntimeFacade(
            @Value("${meguri.capability.store-mode:${meguri.turn-journal.mode:in-memory}}")
                    String storeMode,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectMapper mapper) {
        String mode = storeMode == null
                ? "in-memory" : storeMode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("in-memory".equals(mode)) {
            return new CapabilityRuntimeFacade(32);
        }
        if (!"postgres".equals(mode)) {
            throw new IllegalArgumentException(
                    "unsupported capability store mode: " + mode);
        }
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException(
                    "PostgreSQL capability runtime requires JdbcTemplate");
        }
        JdbcCapabilityRuntimeStore store =
                new JdbcCapabilityRuntimeStore(jdbc, mapper);
        return new CapabilityRuntimeFacade(
                new PersistingCapabilityCatalog(store::saveDefinition),
                new DefaultCapabilityPolicy(),
                store,
                store,
                new DefaultResultNormalizer(),
                store,
                store,
                32);
    }

    @Bean
    public McpSourceStore meguriMcpSourceStore(
            @Value("${meguri.capability.store-mode:${meguri.turn-journal.mode:in-memory}}")
                    String storeMode,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectMapper mapper) {
        String mode = normalizedCapabilityStoreMode(storeMode);
        if ("in-memory".equals(mode)) {
            return new InMemoryMcpSourceStore();
        }
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException(
                    "PostgreSQL MCP source store requires JdbcTemplate");
        }
        return new JdbcMcpSourceStore(jdbc, mapper);
    }

    @Bean
    public RagProvider meguriRagProvider(
            @Value("${meguri.data-root:../../datasets/meguri}") String dataRoot,
            @Value("${meguri.build-id:meguri_local_mock}") String buildId,
            @Value("${meguri.rag.bridge-enabled:false}") boolean bridgeEnabled,
            @Value("${meguri.rag.bridge-url:http://127.0.0.1:8000}") String bridgeUrl,
            @Value("${meguri.rag.bridge-token:}") String bridgeToken,
            @Value("${meguri.rag.bridge-token-file:}") String bridgeTokenFile,
            @Value("${meguri.rag.timeout-ms:2500}") long timeoutMs,
            WebClient.Builder webClientBuilder,
            ObjectMapper mapper) {
        Path root = Path.of(dataRoot);
        RagProvider fallback = new MockRagProvider(root, mapper, resolveBuildId(root, buildId, mapper));
        if (!bridgeEnabled) return fallback;
        return new PythonRagGateway(webClientBuilder, bridgeUrl, bridgeToken,
                bridgeTokenFile, timeoutMs, fallback);
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

    @Bean
    public WeatherGateway meguriWeatherGateway(
            @Value("${meguri.weather.base-url:https://api.open-meteo.com/v1/forecast}") String baseUrl,
            @Value("${meguri.weather.rain-probability-threshold:50}") int rainThreshold,
            @Value("${meguri.weather.rain-lookahead-hours:2}") int rainLookaheadHours) {
        java.net.URI endpoint = java.net.URI.create(baseUrl);
        boolean loopback = endpoint.getHost() != null
                && (endpoint.getHost().equals("127.0.0.1") || endpoint.getHost().equals("localhost"));
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !loopback) {
            throw new IllegalArgumentException("weather base URL must use HTTPS or loopback HTTP");
        }
        WebClient client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(true)))
                .build();
        return new OpenMeteoWeatherGateway(client, baseUrl, Clock.systemUTC(), rainThreshold, rainLookaheadHours);
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

    private static String normalizedCapabilityStoreMode(String storeMode) {
        String mode = storeMode == null
                ? "in-memory" : storeMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"in-memory".equals(mode) && !"postgres".equals(mode)) {
            throw new IllegalArgumentException(
                    "unsupported capability store mode: " + mode);
        }
        return mode;
    }
}
