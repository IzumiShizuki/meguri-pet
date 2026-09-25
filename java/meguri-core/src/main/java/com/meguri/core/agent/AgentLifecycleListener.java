package com.meguri.core.agent;

@FunctionalInterface
public interface AgentLifecycleListener {
    void onEvent(AgentLifecycleEvent event);

    static AgentLifecycleListener noop() {
        return ignored -> {
        };
    }
}
