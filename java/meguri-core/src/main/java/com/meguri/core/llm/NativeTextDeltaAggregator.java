package com.meguri.core.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/** Batches provider tokens without delaying the first visible text beyond the flush interval. */
public final class NativeTextDeltaAggregator {
    private NativeTextDeltaAggregator() { }

    public static Flux<String> aggregate(Flux<String> tokens) {
        return aggregate(tokens, 80, Duration.ofMillis(50));
    }

    public static Flux<String> aggregate(Flux<String> tokens, int maxCharacters, Duration flushInterval) {
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(flushInterval, "flushInterval");
        if (maxCharacters < 20 || maxCharacters > 80) {
            throw new IllegalArgumentException("maxCharacters must be between 20 and 80");
        }
        if (flushInterval.isZero() || flushInterval.isNegative()) {
            throw new IllegalArgumentException("flushInterval must be positive");
        }
        return Flux.defer(() -> Flux.create(sink -> {
            Object lock = new Object();
            StringBuilder pending = new StringBuilder(maxCharacters);
            AtomicBoolean terminated = new AtomicBoolean();

            Runnable flush = () -> {
                synchronized (lock) {
                    if (!terminated.get() && !pending.isEmpty()) {
                        sink.next(pending.toString());
                        pending.setLength(0);
                    }
                }
            };
            Disposable timer = Schedulers.parallel().schedulePeriodically(
                    flush, flushInterval.toNanos(), flushInterval.toNanos(),
                    java.util.concurrent.TimeUnit.NANOSECONDS);
            Disposable upstream = tokens.subscribe(token -> {
                if (token == null || token.isEmpty()) return;
                synchronized (lock) {
                    if (terminated.get()) return;
                    int offset = 0;
                    while (offset < token.length()) {
                        int copied = Math.min(maxCharacters - pending.length(), token.length() - offset);
                        pending.append(token, offset, offset + copied);
                        offset += copied;
                        if (pending.length() == maxCharacters) {
                            sink.next(pending.toString());
                            pending.setLength(0);
                        }
                    }
                }
            }, error -> {
                synchronized (lock) {
                    if (!terminated.compareAndSet(false, true)) return;
                    if (!pending.isEmpty()) sink.next(pending.toString());
                    pending.setLength(0);
                    timer.dispose();
                    sink.error(error);
                }
            }, () -> {
                synchronized (lock) {
                    if (!terminated.compareAndSet(false, true)) return;
                    if (!pending.isEmpty()) sink.next(pending.toString());
                    pending.setLength(0);
                    timer.dispose();
                    sink.complete();
                }
            });
            sink.onDispose(() -> {
                terminated.set(true);
                timer.dispose();
                upstream.dispose();
            });
        }, FluxSink.OverflowStrategy.BUFFER));
    }
}
