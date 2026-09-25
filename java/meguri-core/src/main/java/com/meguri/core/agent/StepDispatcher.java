package com.meguri.core.agent;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.function.Supplier;

public final class StepDispatcher implements AutoCloseable {
    private final Scheduler blockingIo;
    private final Scheduler cpu;
    private final Scheduler provider;
    private final Scheduler background;

    public StepDispatcher(int blockingThreads, int cpuThreads, int providerThreads, int backgroundThreads) {
        blockingIo = Schedulers.newBoundedElastic(blockingThreads, blockingThreads * 100, "agent-blocking-io");
        cpu = Schedulers.newParallel("agent-cpu", cpuThreads);
        provider = Schedulers.newBoundedElastic(providerThreads, providerThreads * 100, "agent-provider");
        background = Schedulers.newBoundedElastic(backgroundThreads, backgroundThreads * 100, "agent-background");
    }

    public <T> Mono<T> dispatch(ExecutionDomain domain, Supplier<? extends Mono<T>> work) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(work, "work");
        Mono<T> deferred = Mono.defer(work);
        return switch (domain) {
            case INLINE, NON_BLOCKING_IO, REMOTE_AGENT -> deferred;
            case BLOCKING_IO -> deferred.subscribeOn(blockingIo);
            case CPU -> deferred.subscribeOn(cpu);
            case PROVIDER -> deferred.subscribeOn(provider);
            case BACKGROUND -> deferred.subscribeOn(background);
        };
    }

    @Override
    public void close() {
        blockingIo.dispose();
        cpu.dispose();
        provider.dispose();
        background.dispose();
    }
}
