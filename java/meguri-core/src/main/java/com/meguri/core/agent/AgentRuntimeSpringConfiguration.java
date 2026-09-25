package com.meguri.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.util.Map;

/**
 * Standalone Agent runtime assembly. A production RemoteAgentGateway bean wins;
 * otherwise the deterministic in-memory gateway is installed as an explicit
 * local-development fallback.
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeSpringConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentRuntimeFactory.Config.class)
    AgentRuntimeFactory.Config agentRuntimeConfig() {
        return AgentRuntimeFactory.Config.defaults();
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntimePolicy.class)
    AgentRuntimePolicy agentRuntimePolicy(AgentRuntimeFactory.Config config) {
        return AgentRuntimePolicy.defaults(config);
    }

    @Bean
    @ConditionalOnMissingBean(AgentExecutionStores.RuntimeStore.class)
    AgentExecutionStores.RuntimeStore agentRuntimeStore(
            @Value("${meguri.agent.store-mode:${meguri.turn-journal.mode:in-memory}}")
                    String storeMode,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectProvider<ObjectMapper> mapperProvider) {
        String mode = storeMode == null
                ? "in-memory" : storeMode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("in-memory".equals(mode)) return new InMemoryAgentRuntimeStore();
        if (!"postgres".equals(mode)) {
            throw new IllegalArgumentException(
                    "unsupported agent runtime store mode: " + mode);
        }
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        ObjectMapper mapper = mapperProvider.getIfAvailable();
        if (jdbc == null || mapper == null || jdbc.getDataSource() == null) {
            throw new IllegalStateException(
                    "PostgreSQL agent runtime requires JdbcTemplate and ObjectMapper");
        }
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("db/agent-runtime.sql"));
        schema.setContinueOnError(false);
        schema.execute(jdbc.getDataSource());
        return new JdbcAgentRuntimeStore(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(AgentLifecycleListener.class)
    AgentLifecycleListener agentLifecycleListener() {
        return AgentLifecycleListener.noop();
    }

    @Bean
    @ConditionalOnMissingBean(RemoteAgentGateway.class)
    @ConditionalOnProperty(name = "meguri.agent.gateway-mode", havingValue = "in-memory")
    InMemoryRemoteAgentGateway localAgentGatewayFallback(AgentRuntimeFactory.Config config) {
        return new InMemoryRemoteAgentGateway(config.submitDelay(), config.pollsBeforeSuccess());
    }

    @Bean
    @ConditionalOnMissingBean(RemoteAgentGateway.class)
    @ConditionalOnProperty(name = "meguri.agent.gateway-mode", havingValue = "http")
    HttpRemoteAgentGateway httpRemoteAgentGateway(
            ObjectProvider<ObjectMapper> mapperProvider,
            @Value("${meguri.agent.remote.endpoint}") String endpoint,
            @Value("${meguri.agent.remote.authorization-environment:}") String authorizationEnvironment,
            @Value("${meguri.agent.remote.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${meguri.agent.remote.request-timeout-ms:10000}") long requestTimeoutMs,
            @Value("${meguri.agent.remote.maximum-response-characters:262144}") int maximumResponseCharacters,
            @Value("${meguri.agent.remote.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        String authorization = null;
        if (authorizationEnvironment != null && !authorizationEnvironment.isBlank()) {
            authorization = System.getenv(authorizationEnvironment.trim());
            if (authorization == null || authorization.isBlank()) {
                throw new IllegalStateException(
                        "remote agent authorization environment is not set");
            }
        }
        ObjectMapper mapper = mapperProvider.getIfAvailable(
                () -> new ObjectMapper().findAndRegisterModules());
        return new HttpRemoteAgentGateway(new HttpRemoteAgentGateway.Config(
                URI.create(endpoint), authorization, Map.of(),
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(requestTimeoutMs),
                maximumResponseCharacters, allowInsecureLocalhost), mapper);
    }

    @Bean
    @ConditionalOnMissingBean(RemoteAgentGateway.class)
    @ConditionalOnProperty(
            name = "meguri.agent.gateway-mode",
            havingValue = "unavailable",
            matchIfMissing = true)
    UnavailableRemoteAgentGateway unavailableAgentGateway() {
        return new UnavailableRemoteAgentGateway(
                "remote agent gateway is not configured");
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentRuntimeAssembly.class)
    AgentRuntimeAssembly agentRuntimeAssembly(
            AgentRuntimeFactory.Config config,
            AgentLifecycleListener listener,
            RemoteAgentGateway gateway,
            AgentExecutionStores.RuntimeStore store) {
        return AgentRuntimeFactory.create(
                config, listener, Clock.systemUTC(), gateway, store);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
    AgentRuntime agentRuntime(AgentRuntimeAssembly assembly) {
        return assembly.runtime();
    }

    @Bean
    @ConditionalOnMissingBean(AgentDurableRecoveryLifecycle.class)
    AgentDurableRecoveryLifecycle agentDurableRecoveryLifecycle(
            AgentRuntime runtime,
            @Value("${meguri.agent.recovery.interval-ms:5000}") long intervalMs,
            @Value("${meguri.agent.recovery.batch-size:32}") int batchSize) {
        return new AgentDurableRecoveryLifecycle(
                runtime, Duration.ofMillis(intervalMs), batchSize);
    }
}
