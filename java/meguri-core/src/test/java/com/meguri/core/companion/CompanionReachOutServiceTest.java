package com.meguri.core.companion;

import com.meguri.core.llm.LlmProvider;
import com.meguri.core.runtime.SessionContextStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class CompanionReachOutServiceTest {
    private final LlmProvider provider = mock(LlmProvider.class);
    private final SessionContextStore contexts = new SessionContextStore();
    private final CompanionReachOutService.Scope scope = new CompanionReachOutService.Scope("owner", "desktop_pet", "s1");
    private final CompanionReachOutService service = new CompanionReachOutService(provider, contexts,
            Clock.fixed(Instant.parse("2026-10-22T02:00:00Z"), ZoneOffset.UTC));

    private void completedExchange(String session) {
        contexts.appendNode("owner", "desktop_pet", session, "user-1", null, new SessionContextStore.Message("user", "我最近在看一本书"));
        contexts.appendNode("owner", "desktop_pet", session, "assistant-1", null, new SessionContextStore.Message("assistant", "兄长，是什么书呢？"));
    }

    @Test
    void cachesDraftsWithoutWritingUntilIdempotentDeliveryAcknowledgement() {
        completedExchange("s1");
        when(provider.generateReachOut(anyList())).thenReturn(Mono.just("兄长，要不要接着聊聊那本书？"));

        var candidate = service.draft(scope).block();
        assertThat(candidate).isNotNull();
        assertThat(service.draft(scope).block()).isEqualTo(candidate);
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(2);
        verify(provider, times(1)).generateReachOut(anyList());
        assertThat(service.acknowledge(scope, candidate.id()).block()).isTrue();
        assertThat(service.acknowledge(scope, candidate.id()).block()).isTrue();
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(3)
                .last().isEqualTo(new SessionContextStore.Message("assistant", candidate.text()));
        assertThat(service.draft(scope).block()).isNull();
    }

    @Test
    void rejectsForeignScopeAndStaleAcknowledgementWithoutChangingTheNewBranch() {
        completedExchange("s1");
        when(provider.generateReachOut(anyList())).thenReturn(Mono.just("一句搭话"));
        var candidate = service.draft(scope).block();
        var other = new CompanionReachOutService.Scope("another-owner", "desktop_pet", "s1");

        assertThat(service.draft(other).block()).isNull();
        assertThat(service.acknowledge(other, candidate.id()).block()).isFalse();
        contexts.appendNode("owner", "desktop_pet", "s1", "user-2", null, new SessionContextStore.Message("user", "换个话题"));
        assertThat(service.acknowledge(scope, candidate.id()).block()).isFalse();
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(3).last()
                .isEqualTo(new SessionContextStore.Message("user", "换个话题"));
    }

    @Test
    void discardsAnInFlightCandidateWhenAUserMessageArrives() {
        completedExchange("s1");
        Sinks.One<String> output = Sinks.one();
        when(provider.generateReachOut(anyList())).thenReturn(output.asMono());
        var first = service.draft(scope).toFuture();
        // Wait for subscription before advancing the context, with a bounded verification wait.
        verify(provider, timeout(1000)).generateReachOut(anyList());
        contexts.append("owner", "desktop_pet", "s1", new SessionContextStore.Message("user", "先别说"));
        output.tryEmitValue("不应显示");
        assertThat(first.join()).isNull();
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(3);
    }

    @Test
    void sharesConcurrentAttemptsAndCachesDeclinesAndFailures() {
        completedExchange("s1");
        when(provider.generateReachOut(anyList())).thenReturn(Mono.error(new IllegalStateException("offline")));

        assertThat(Mono.zip(service.draft(scope).defaultIfEmpty(new CompanionReachOutService.Candidate("", "", 0, 0)),
                service.draft(scope).defaultIfEmpty(new CompanionReachOutService.Candidate("", "", 0, 0))).block()).isNotNull();
        assertThat(service.draft(scope).block()).isNull();
        verify(provider, times(1)).generateReachOut(anyList());
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(2);
    }

    @Test
    void noCompletedExchangeMeansNoModelCall() {
        contexts.append("owner", "desktop_pet", "s1", new SessionContextStore.Message("user", "hello"));
        assertThat(service.draft(scope).block()).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    void expiredDraftCannotBeAcknowledgedIntoContext() {
        completedExchange("s1");
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-10-22T02:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.millis()).thenReturn(now.toEpochMilli());
        when(provider.generateReachOut(anyList())).thenReturn(Mono.just("一句搭话"));
        var expiring = new CompanionReachOutService(provider, contexts, clock);
        var candidate = expiring.draft(scope).block();

        when(clock.millis()).thenReturn(candidate.expiresAt());
        assertThat(expiring.acknowledge(scope, candidate.id()).block()).isFalse();
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(2);
    }
}
