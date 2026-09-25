package com.meguri.core.agent;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Sinks.Empty<Void> signal = Sinks.empty();
    private final CopyOnWriteArrayList<CancellationToken> children = new CopyOnWriteArrayList<>();

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        children.forEach(CancellationToken::cancel);
        signal.tryEmitEmpty();
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public Mono<Void> onCancel() {
        return isCancelled() ? Mono.empty() : signal.asMono();
    }

    public CancellationToken child() {
        CancellationToken child = new CancellationToken();
        children.add(child);
        if (isCancelled()) child.cancel();
        return child;
    }
}
