package com.meguri.core.agent;

import com.meguri.core.runtime.TurnOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Projects the durable agent state machine into the owning Turn event stream.
 * ObjectProvider avoids a construction cycle with the runtime orchestrator.
 */
@Component
public final class TurnAgentLifecycleListener implements AgentLifecycleListener {
    private final ObjectProvider<TurnOrchestrator> orchestrator;

    public TurnAgentLifecycleListener(
            ObjectProvider<TurnOrchestrator> orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
    }

    @Override
    public void onEvent(AgentLifecycleEvent event) {
        TurnOrchestrator current = orchestrator.getIfAvailable();
        if (current != null) current.recordAgentLifecycle(event);
    }
}
