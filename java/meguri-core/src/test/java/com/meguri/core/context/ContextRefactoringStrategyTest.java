package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRefactoringStrategyTest {
    @Test
    void invalidSemanticOutputFallsBackToDeterministicFacts() {
        SessionContextStore.MessageNode source = new SessionContextStore.MessageNode(
                "source-1", null, "user", "remember the exact route", java.time.Instant.now());
        ContextRefactoringStrategy invalid = new ContextRefactoringStrategy() {
            @Override public String revision() { return "semantic-invalid"; }
            @Override public Output refactor(Input input) {
                return new Output(StructuredContextSummary.builder("invalid")
                        .fact(new StructuredContextSummary.Fact(
                                "bad", "invented", List.of("not-in-range"),
                                StructuredContextSummary.Importance.NORMAL,
                                StructuredContextSummary.FactStatus.ACTIVE, List.of()))
                        .build(), "invalid");
            }
        };

        ContextRefactoringStrategy fallback = new FallbackContextRefactoringStrategy(
                invalid, new DeterministicContextRefactoringStrategy(1_000));
        ContextRefactoringStrategy.Output result = fallback.refactor(
                new ContextRefactoringStrategy.Input(List.of(source), "model"));

        assertThat(result.structured().facts()).singleElement()
                .satisfies(fact -> assertThat(fact.sourceIds()).containsExactly("source-1"));
    }

    @Test
    void semanticTimeoutUsesDeterministicFallback() {
        SessionContextStore.MessageNode source = new SessionContextStore.MessageNode(
                "source-1", null, "user", "keep this constraint", java.time.Instant.now());
        ContextRefactoringStrategy slow = new ContextRefactoringStrategy() {
            @Override public String revision() { return "semantic-slow"; }
            @Override public Output refactor(Input input) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new DeterministicContextRefactoringStrategy(1_000).refactor(input);
            }
        };

        ContextRefactoringStrategy fallback = new FallbackContextRefactoringStrategy(
                slow, new DeterministicContextRefactoringStrategy(1_000), Duration.ofMillis(5));
        ContextRefactoringStrategy.Output result = fallback.refactor(
                new ContextRefactoringStrategy.Input(List.of(source), "model"));

        assertThat(result.structured().facts()).isNotEmpty();
    }
}
