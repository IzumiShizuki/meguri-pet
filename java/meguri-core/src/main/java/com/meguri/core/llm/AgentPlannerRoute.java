package com.meguri.core.llm;

import dev.langchain4j.model.chat.ChatModel;
import java.time.Duration;
import java.util.Objects;

/** Isolated control-plane resources for optional Agent planning. */
public record AgentPlannerRoute(
        ChatModel model,
        int maxConcurrency,
        Duration queueTimeout) {
    public AgentPlannerRoute {
        model = Objects.requireNonNull(model, "planner model");
        if (maxConcurrency <= 0) {
            throw new LlmConfigurationException("planner max concurrency must be positive");
        }
        queueTimeout = Objects.requireNonNull(queueTimeout, "planner queue timeout");
        if (queueTimeout.isNegative()) {
            throw new LlmConfigurationException("planner queue timeout must not be negative");
        }
    }

    public static AgentPlannerRoute compatibilityDefault(ChatModel model) {
        return new AgentPlannerRoute(model, 1, Duration.ofMillis(250));
    }
}
