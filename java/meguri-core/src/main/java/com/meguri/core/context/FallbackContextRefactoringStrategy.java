package com.meguri.core.context;

import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Validates an optional strategy result and falls back to a deterministic strategy. */
public final class FallbackContextRefactoringStrategy implements ContextRefactoringStrategy {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "context-semantic-refactoring");
        thread.setDaemon(true);
        return thread;
    });
    private final ContextRefactoringStrategy primary;
    private final ContextRefactoringStrategy fallback;
    private final Duration timeout;

    public FallbackContextRefactoringStrategy(
            ContextRefactoringStrategy primary, ContextRefactoringStrategy fallback) {
        this(primary, fallback, Duration.ofSeconds(2));
    }

    public FallbackContextRefactoringStrategy(
            ContextRefactoringStrategy primary,
            ContextRefactoringStrategy fallback,
            Duration timeout) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public String revision() {
        return primary.revision() + "+fallback-" + fallback.revision();
    }

    @Override
    public Output refactor(Input input) {
        CompletableFuture<Output> future = null;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> primary.refactor(input), EXECUTOR);
            Output result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            validateSources(result, input);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fallback.refactor(input);
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            return fallback.refactor(input);
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private static void validateSources(Output output, Input input) {
        java.util.Set<String> sourceIds = input.sourceMessages().stream()
                .map(com.meguri.core.runtime.SessionContextStore.MessageNode::messageId)
                .collect(java.util.stream.Collectors.toSet());
        for (StructuredContextSummary.Fact fact : output.structured().facts()) {
            if (!sourceIds.containsAll(fact.sourceIds())) {
                throw new IllegalArgumentException("strategy returned a source outside the frozen range");
            }
        }
        for (StructuredContextSummary.Operation operation : output.structured().operations()) {
            if (!sourceIds.containsAll(operation.sourceIds())) {
                throw new IllegalArgumentException("strategy operation returned an outside source");
            }
        }
        output.structured().validate();
    }
}
