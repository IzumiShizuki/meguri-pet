package com.meguri.core.agent;

import com.meguri.core.adapter.domain.ReplayPolicy;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.TurnOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TurnAgentLifecycleProjectionTest {
    @Test
    void agentLifecycleIsPersistedOnTheOwningTurnWithStateReplay() {
        TurnOrchestrator orchestrator = new TurnOrchestrator();
        TurnRequest request =
                new TurnRequest("user", "website", "agent-events", "hello");
        try {
            orchestrator.runInline(request).block(Duration.ofSeconds(5));
            String turnId = orchestrator.turns().keySet().iterator().next();

            orchestrator.recordAgentLifecycle(new AgentLifecycleEvent(
                    7,
                    Instant.now(),
                    AgentLifecycleEvent.Type.DURABLE_WAITING,
                    turnId,
                    "skill-1",
                    "step-1",
                    "task-1",
                    "remote-1",
                    "RUNNING",
                    "WAITING_EXTERNAL",
                    "durable"));

            assertThat(orchestrator.eventsFor(request.getSessionId()).getLast())
                    .satisfies(event -> {
                        assertThat(event.getType()).isEqualTo("agent.waiting");
                        assertThat(event.getReplayPolicy()).isEqualTo(ReplayPolicy.STATE);
                        assertThat(event.getData())
                                .containsEntry("task_id", "task-1")
                                .containsEntry("lifecycle_sequence", 7L);
                    });
        } finally {
            orchestrator.reset();
        }
    }
}
