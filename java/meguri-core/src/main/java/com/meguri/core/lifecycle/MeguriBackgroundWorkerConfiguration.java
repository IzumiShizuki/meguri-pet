package com.meguri.core.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.context.ContextPrecompressionWorker;
import com.meguri.core.context.ContextRefactoringStrategy;
import com.meguri.core.context.ContextRuntimePersistence;
import com.meguri.core.context.DeterministicContextRefactoringStrategy;
import com.meguri.core.context.FallbackContextRefactoringStrategy;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.job.InMemoryPostReplyMemoryJobStore;
import com.meguri.core.memory.job.JdbcPostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJobEnqueuer;
import com.meguri.core.memory.job.PostReplyMemoryOutboxDelivery;
import com.meguri.core.memory.job.PostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJobWorker;
import com.meguri.core.runtime.TurnJournal;
import com.meguri.core.runtime.TurnOutboxDispatcher;
import com.meguri.core.runtime.SessionContextStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

/** Independent lifecycle assembly for durable Turn delivery and post-reply memory work. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MeguriBackgroundWorkerProperties.class, MeguriContextProperties.class})
public class MeguriBackgroundWorkerConfiguration {

    @Bean
    @ConditionalOnMissingBean(PostReplyMemoryJobStore.class)
    PostReplyMemoryJobStore postReplyMemoryJobStore(
            MeguriBackgroundWorkerProperties properties,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectMapper mapper) {
        String mode = normalizedMode(properties.getMemory().getStoreMode());
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        boolean postgresAvailable = dataSource != null && jdbc != null && jdbc.getDataSource() != null;

        if ("in-memory".equals(mode) || "auto".equals(mode) && !postgresAvailable) {
            return new InMemoryPostReplyMemoryJobStore();
        }
        if (!postgresAvailable) {
            throw new IllegalStateException(
                    "PostgreSQL post-reply memory store requires DataSource and JdbcTemplate");
        }
        if (properties.getMemory().isInitializeSchema()) {
            ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                    new ClassPathResource("db/post-reply-memory-job.sql"));
            schema.setContinueOnError(false);
            schema.execute(dataSource);
        }
        return new JdbcPostReplyMemoryJobStore(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(PostReplyMemoryJobEnqueuer.class)
    PostReplyMemoryJobEnqueuer postReplyMemoryJobEnqueuer(
            PostReplyMemoryJobStore store, ObjectMapper mapper,
            ObjectProvider<Clock> clockProvider) {
        return new PostReplyMemoryJobEnqueuer(store, mapper,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(PostReplyMemoryOutboxDelivery.class)
    PostReplyMemoryOutboxDelivery postReplyMemoryOutboxDelivery(
            TurnJournal journal, PostReplyMemoryJobEnqueuer enqueuer) {
        return new PostReplyMemoryOutboxDelivery(journal, enqueuer);
    }

    @Bean
    @ConditionalOnMissingBean(PostReplyMemoryJobWorker.class)
    @ConditionalOnProperty(name = "meguri.background-workers.memory.enabled",
            havingValue = "true", matchIfMissing = true)
    PostReplyMemoryJobWorker postReplyMemoryJobWorker(
            PostReplyMemoryJobStore store, MemoryGateway gateway,
            ObjectProvider<Clock> clockProvider,
            MeguriBackgroundWorkerProperties properties) {
        var config = properties.getMemory();
        return new PostReplyMemoryJobWorker(store, gateway,
                clockProvider.getIfAvailable(Clock::systemUTC), ownerId("memory"),
                config.getLease(), config.getWriteTimeout(), config.getBatchSize(),
                config.getMaxAttempts(), config.getInitialBackoff());
    }

    @Bean
    @ConditionalOnBean(PostReplyMemoryJobWorker.class)
    SafePollingLifecycle postReplyMemoryJobLifecycle(
            PostReplyMemoryJobWorker worker, MeguriBackgroundWorkerProperties properties) {
        return new SafePollingLifecycle("post-reply-memory", worker::runOnce,
                worker, properties.getMemory().getPollInterval());
    }

    @Bean
    @ConditionalOnMissingBean(ContextPrecompressionWorker.class)
    @ConditionalOnBean({SessionContextStore.class, ContextRuntimePersistence.class})
    @ConditionalOnProperty(name = "meguri.background-workers.context.enabled",
            havingValue = "true", matchIfMissing = true)
    ContextPrecompressionWorker contextPrecompressionWorker(
            SessionContextStore sessions,
            ContextRuntimePersistence persistence,
            ObjectProvider<Clock> clockProvider,
            MeguriBackgroundWorkerProperties properties,
            MeguriContextProperties contextProperties,
            ObjectProvider<ContextRefactoringStrategy> strategyProvider) {
        var config = properties.getContext();
        ContextRefactoringStrategy deterministic =
                new DeterministicContextRefactoringStrategy(config.getMaximumSummaryCharacters());
        ContextRefactoringStrategy strategy = deterministic;
        if (contextProperties.isSemanticCompressionEnabled()) {
            ContextRefactoringStrategy optional = strategyProvider.getIfAvailable();
            if (optional != null) {
                strategy = new FallbackContextRefactoringStrategy(optional, deterministic);
            }
        }
        return new ContextPrecompressionWorker(
                sessions, persistence, clockProvider.getIfAvailable(Clock::systemUTC),
                config.getLease(), config.getRetryBackoff(), config.getBatchSize(),
                config.getMaxAttempts(), config.getMaximumSummaryCharacters(), strategy,
                contextProperties.isStructuredCompressionEnabled());
    }

    @Bean
    @ConditionalOnBean(ContextPrecompressionWorker.class)
    SafePollingLifecycle contextPrecompressionLifecycle(
            ContextPrecompressionWorker worker,
            MeguriBackgroundWorkerProperties properties) {
        return new SafePollingLifecycle("context-precompression", worker::runOnce,
                worker, properties.getContext().getPollInterval());
    }

    @Bean
    @ConditionalOnMissingBean(TurnOutboxDispatcher.class)
    @ConditionalOnProperty(name = "meguri.background-workers.outbox.enabled",
            havingValue = "true", matchIfMissing = true)
    TurnOutboxDispatcher turnOutboxDispatcher(
            TurnJournal journal,
            ObjectProvider<TurnOutboxDispatcher.Delivery> deliveryProvider,
            MeguriBackgroundWorkerProperties properties) {
        var config = properties.getOutbox();
        List<TurnOutboxDispatcher.Delivery> deliveries =
                deliveryProvider.orderedStream().toList();
        if (deliveries.isEmpty()) {
            throw new IllegalStateException("Turn outbox requires at least one delivery");
        }
        TurnOutboxDispatcher.Delivery composite = (eventId, event) -> {
            for (TurnOutboxDispatcher.Delivery delivery : deliveries) {
                delivery.deliver(eventId, event);
            }
        };
        return new TurnOutboxDispatcher(journal, ownerId("outbox"), composite,
                config.getLease(), config.getBatchSize(), config.getDeadLetterAfter());
    }

    @Bean
    @ConditionalOnBean(TurnOutboxDispatcher.class)
    SafePollingLifecycle turnOutboxLifecycle(
            TurnOutboxDispatcher dispatcher, MeguriBackgroundWorkerProperties properties) {
        return new SafePollingLifecycle("turn-outbox", dispatcher::dispatchOnce,
                dispatcher, properties.getOutbox().getPollInterval());
    }

    private static String normalizedMode(String value) {
        String mode = value == null ? "auto" : value.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode) && !"in-memory".equals(mode) && !"postgres".equals(mode)) {
            throw new IllegalArgumentException("unsupported post-reply memory store mode: " + mode);
        }
        return mode;
    }

    private static String ownerId(String worker) {
        return "meguri-" + worker + "-" + ManagementFactory.getRuntimeMXBean().getName()
                + "-" + UUID.randomUUID();
    }
}
