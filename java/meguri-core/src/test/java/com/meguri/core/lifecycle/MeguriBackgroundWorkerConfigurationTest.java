package com.meguri.core.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.memory.SessionSummaryRequest;
import com.meguri.core.memory.SessionSummaryResult;
import com.meguri.core.memory.job.InMemoryPostReplyMemoryJobStore;
import com.meguri.core.memory.job.JdbcPostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJobEnqueuer;
import com.meguri.core.memory.job.PostReplyMemoryOutboxDelivery;
import com.meguri.core.memory.job.PostReplyMemoryJobStore;
import com.meguri.core.runtime.InMemoryTurnJournal;
import com.meguri.core.runtime.TurnJournal;
import com.meguri.core.runtime.TurnOutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class MeguriBackgroundWorkerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MeguriBackgroundWorkerConfiguration.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(MemoryGateway.class, SuccessfulMemoryGateway::new)
            .withBean(TurnJournal.class, () -> new InMemoryTurnJournal(
                    new ObjectMapper().findAndRegisterModules()));

    @Test
    void defaultConfigurationStartsMemoryAndTransactionalOutboxWorkers() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PostReplyMemoryJobStore.class);
            assertThat(context.getBean(PostReplyMemoryJobStore.class))
                    .isInstanceOf(InMemoryPostReplyMemoryJobStore.class);
            assertThat(context).hasSingleBean(PostReplyMemoryJobEnqueuer.class);
            assertThat(context).hasSingleBean(PostReplyMemoryOutboxDelivery.class);
            assertThat(context).hasSingleBean(TurnOutboxDispatcher.class);
            assertThat(context.getBeansOfType(SafePollingLifecycle.class)).hasSize(2);
            assertThat(context.getBeansOfType(SafePollingLifecycle.class).values())
                    .allSatisfy(lifecycle -> assertThat(lifecycle.isRunning()).isTrue());
        });
    }

    @Test
    void autoModeDoesNotSelectPostgresWithoutDataSource() {
        runner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PostReplyMemoryJobStore.class))
                            .isInstanceOf(InMemoryPostReplyMemoryJobStore.class);
                });
    }

    @Test
    void explicitPostgresFailsClosedWithoutDataSource() {
        runner.withPropertyValues("meguri.background-workers.memory.store-mode=postgres")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("DataSource and JdbcTemplate");
                });
    }

    @Test
    void postgresModeUsesJdbcStoreWhenInfrastructureIsPresent() {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        runner.withPropertyValues(
                        "meguri.background-workers.memory.store-mode=postgres",
                        "meguri.background-workers.memory.initialize-schema=false",
                        "meguri.background-workers.memory.enabled=false")
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PostReplyMemoryJobStore.class))
                            .isInstanceOf(JdbcPostReplyMemoryJobStore.class);
                });
    }

    @Test
    void memoryLifecyclePollsQueuedWorkAndStopsWithContext() {
        runner.withPropertyValues("meguri.background-workers.memory.poll-interval=5ms")
                .run(context -> {
                    PostReplyMemoryJobStore store = context.getBean(PostReplyMemoryJobStore.class);
                    var result = context.getBean(PostReplyMemoryJobEnqueuer.class).enqueue(
                            "turn-1", new TurnRequest("user", "website", "session", "hello",
                                    List.of(), new ClientCapabilities(), null, null, true),
                            new LlmResponse("reply"), "trace-1", false,
                            com.meguri.core.memory.job.PostReplyMemoryJob.CancellationPolicy
                                    .PROCESS_COMPLETED_REPLY);

                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            assertThat(store.find(result.job().jobId()).orElseThrow().status())
                                    .isEqualTo(com.meguri.core.memory.job.PostReplyMemoryJob.Status.SUCCEEDED));
                });
    }

    @Test
    void outboxStartsOnlyWithExplicitDeliveryAndPollsSafely() {
        TurnJournal journal = mock(TurnJournal.class);
        TurnOutboxDispatcher.Delivery delivery = (eventId, event) -> { };
        runner.withPropertyValues(
                        "meguri.background-workers.memory.enabled=false",
                        "meguri.background-workers.outbox.poll-interval=5ms")
                .withBean("testJournal", TurnJournal.class, () -> journal,
                        definition -> ((AbstractBeanDefinition) definition).setPrimary(true))
                .withBean(TurnOutboxDispatcher.Delivery.class, () -> delivery)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TurnOutboxDispatcher.class);
                    assertThat(context).hasSingleBean(SafePollingLifecycle.class);
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            verify(journal, atLeastOnce()).claimOutbox(
                                    org.mockito.ArgumentMatchers.anyString(),
                                    org.mockito.ArgumentMatchers.eq(1),
                                    org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30))));
                });
    }

    @Test
    void pollingFailureDoesNotKillLoopAndStopClosesOwnedResource() {
        AtomicInteger polls = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        SafePollingLifecycle lifecycle = new SafePollingLifecycle("resilient-test", () -> {
            if (polls.incrementAndGet() == 1) throw new IllegalStateException("transient");
        }, () -> closed.set(true), Duration.ofMillis(5));

        lifecycle.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> polls.get() >= 2);
        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(closed).isTrue();
    }

    private static final class SuccessfulMemoryGateway implements MemoryGateway {
        @Override public Mono<MemoryRecall> recall(TurnRequest request) {
            return Mono.just(MemoryRecall.unavailable());
        }
        @Override public Mono<List<MemoryCandidate>> extract(TurnRequest request) {
            return Mono.just(List.of());
        }
        @Override public Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response,
                                                       String turnId, String traceId) {
            return Mono.just(new MemoryWriteResult("success", List.of(), List.of(), List.of(), List.of()));
        }
        @Override public Mono<SessionSummaryResult> summarize(SessionSummaryRequest request) {
            return Mono.just(SessionSummaryResult.unavailable(
                    request.userId(), request.clientId(), request.sessionId()));
        }
    }
}
