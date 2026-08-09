package com.meguri.core.llm;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NativeTextDeltaAggregatorTest {
    @Test
    void preservesTextAndCapsPersistedDeltaSize() {
        String input = "a".repeat(173);

        var chunks = NativeTextDeltaAggregator.aggregate(
                        Flux.just(input), 80, Duration.ofMillis(10))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertThat(chunks).isNotNull().allSatisfy(chunk -> assertThat(chunk.length()).isBetween(1, 80));
        assertThat(String.join("", chunks)).isEqualTo(input);
    }

    @Test
    void flushesShortVisibleTextOnTimeBoundary() {
        long started = System.nanoTime();
        String value = NativeTextDeltaAggregator.aggregate(
                        Flux.concat(Flux.just("short"), Flux.never()),
                        80, Duration.ofMillis(50))
                .next()
                .block(Duration.ofSeconds(1));

        assertThat(value).isEqualTo("short");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void flushesFirstShortTokenImmediatelyRegardlessOfBatchWindow() {
        String value = NativeTextDeltaAggregator.aggregate(
                        Flux.concat(Flux.just("first"), Flux.never()),
                        80, Duration.ofSeconds(5))
                .blockFirst(Duration.ofMillis(300));

        assertThat(value).isEqualTo("first");
    }

    @Test
    void flushesAtCharacterLimitWithoutWaitingForTimer() {
        String value = NativeTextDeltaAggregator.aggregate(
                        Flux.just("x".repeat(80)).concatWith(Flux.never()),
                        80, Duration.ofSeconds(5))
                .blockFirst(Duration.ofMillis(300));

        assertThat(value).isEqualTo("x".repeat(80));
    }

    @Test
    void flushesTailBeforeCompletionAndProviderError() {
        var completed = NativeTextDeltaAggregator.aggregate(
                        Flux.just("tail"), 80, Duration.ofSeconds(5))
                .collectList().block(Duration.ofSeconds(1));
        var failedSignals = NativeTextDeltaAggregator.aggregate(
                        Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("lost"))),
                        80, Duration.ofSeconds(5))
                .materialize().collectList().block(Duration.ofSeconds(1));

        assertThat(completed).containsExactly("tail");
        assertThat(failedSignals).isNotNull();
        assertThat(failedSignals.stream().filter(signal -> signal.isOnNext())
                .map(signal -> signal.get()).toList()).containsExactly("partial");
        assertThat(failedSignals.getLast().isOnError()).isTrue();
    }
}
