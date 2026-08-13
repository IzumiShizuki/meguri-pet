package com.meguri.core.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned, provenance-aware projection of an immutable conversation range. */
public record StructuredContextSummary(
        String schemaVersion,
        String gist,
        List<Fact> facts,
        List<String> currentState,
        List<String> decisions,
        List<String> constraints,
        List<String> openThreads,
        List<String> exactItems,
        List<Operation> operations) {
    public static final String CURRENT_SCHEMA = "structured-context-v1";

    public StructuredContextSummary {
        schemaVersion = required(schemaVersion, "schemaVersion");
        gist = gist == null ? "" : gist;
        facts = facts == null ? List.of() : List.copyOf(facts);
        currentState = currentState == null ? List.of() : List.copyOf(currentState);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        openThreads = openThreads == null ? List.of() : List.copyOf(openThreads);
        exactItems = exactItems == null ? List.of() : List.copyOf(exactItems);
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public void validate() {
        if (!CURRENT_SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported structured context schema: " + schemaVersion);
        }
        Set<String> factIds = new HashSet<>();
        for (Fact fact : facts) {
            if (fact == null || !factIds.add(fact.factId())) {
                throw new IllegalArgumentException("structured context facts must have unique IDs");
            }
            if (fact.sourceIds().isEmpty()) {
                throw new IllegalArgumentException("structured context facts require source IDs");
            }
            if (fact.text().isBlank()) {
                throw new IllegalArgumentException("structured context facts require text");
            }
            for (String superseded : fact.supersedesFactIds()) {
                if (fact.factId().equals(superseded)) {
                    throw new IllegalArgumentException("a fact cannot supersede itself");
                }
            }
        }
        for (List<String> category : categories()) {
            if (category.stream().anyMatch(id -> !factIds.contains(id))) {
                throw new IllegalArgumentException("structured context category references an unknown fact");
            }
        }
        for (Fact fact : facts) {
            if (fact.supersedesFactIds().stream().anyMatch(id -> !factIds.contains(id))) {
                throw new IllegalArgumentException("structured context supersession references an unknown fact");
            }
        }
        for (Operation operation : operations) {
            if (operation == null) {
                throw new IllegalArgumentException("structured context operation must not be null");
            }
            if (operation.resultFactId() != null && !factIds.contains(operation.resultFactId())) {
                throw new IllegalArgumentException("structured context operation result is unknown");
            }
            if (operation.factIds().stream().anyMatch(id -> !factIds.contains(id))) {
                throw new IllegalArgumentException("structured context operation references an unknown fact");
            }
        }
    }

    public List<Fact> factsFor(List<String> ids) {
        Set<String> wanted = new HashSet<>(ids == null ? List.of() : ids);
        return facts.stream().filter(fact -> wanted.contains(fact.factId())).toList();
    }

    private List<List<String>> categories() {
        return List.of(currentState, decisions, constraints, openThreads, exactItems);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum Importance { HIGH, NORMAL }

    public enum FactStatus { ACTIVE, SUPERSEDED }

    public record Fact(
            String factId,
            String text,
            List<String> sourceIds,
            Importance importance,
            FactStatus status,
            List<String> supersedesFactIds) {
        public Fact {
            factId = required(factId, "factId");
            text = text == null ? "" : text;
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            importance = importance == null ? Importance.NORMAL : importance;
            status = status == null ? FactStatus.ACTIVE : status;
            supersedesFactIds = supersedesFactIds == null ? List.of() : List.copyOf(supersedesFactIds);
        }
    }

    public enum OperationType { KEEP_EXACT, COMPRESS, DROP, MERGE, SUPERSEDE }

    public record Operation(
            OperationType type,
            List<String> factIds,
            List<String> sourceIds,
            String resultFactId) {
        public Operation {
            type = Objects.requireNonNull(type, "type");
            factIds = factIds == null ? List.of() : List.copyOf(factIds);
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    public static Builder builder(String gist) {
        return new Builder(gist);
    }

    /** Small builder used by deterministic and semantic strategy implementations. */
    public static final class Builder {
        private final String gist;
        private final List<Fact> facts = new ArrayList<>();
        private final List<String> currentState = new ArrayList<>();
        private final List<String> decisions = new ArrayList<>();
        private final List<String> constraints = new ArrayList<>();
        private final List<String> openThreads = new ArrayList<>();
        private final List<String> exactItems = new ArrayList<>();
        private final List<Operation> operations = new ArrayList<>();

        private Builder(String gist) {
            this.gist = gist == null ? "" : gist;
        }

        public Builder fact(Fact fact) {
            facts.add(Objects.requireNonNull(fact, "fact"));
            return this;
        }

        public Builder currentState(String factId) { currentState.add(factId); return this; }
        public Builder decision(String factId) { decisions.add(factId); return this; }
        public Builder constraint(String factId) { constraints.add(factId); return this; }
        public Builder openThread(String factId) { openThreads.add(factId); return this; }
        public Builder exactItem(String factId) { exactItems.add(factId); return this; }
        public Builder operation(Operation operation) { operations.add(operation); return this; }

        public StructuredContextSummary build() {
            StructuredContextSummary result = new StructuredContextSummary(
                    CURRENT_SCHEMA, gist, facts, currentState, decisions, constraints,
                    openThreads, exactItems, operations);
            result.validate();
            return result;
        }
    }
}
