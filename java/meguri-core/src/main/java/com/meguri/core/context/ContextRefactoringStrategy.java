package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.List;
import java.util.Objects;

/** Strategy boundary for asynchronous, derived context refactoring. */
public interface ContextRefactoringStrategy {
    String DEFAULT_REVISION = "context-refactoring-v1-deterministic";

    String revision();

    Output refactor(Input input);

    record Input(List<SessionContextStore.MessageNode> sourceMessages, String modelId) {
        public Input {
            sourceMessages = sourceMessages == null ? List.of() : List.copyOf(sourceMessages);
            modelId = modelId == null ? "" : modelId;
        }
    }

    record Output(StructuredContextSummary structured, String compactContent) {
        public Output {
            structured = Objects.requireNonNull(structured, "structured");
            structured.validate();
            compactContent = compactContent == null ? "" : compactContent;
        }
    }
}
