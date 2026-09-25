package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryCapabilityAudit implements CapabilityAudit {
    private final List<Event> events = new ArrayList<>();

    @Override
    public synchronized void record(Event event) {
        events.add(event);
    }

    @Override
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }
}
