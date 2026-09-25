package com.meguri.core.memory;

import java.util.List;

public record MemoryWriteResult(
        String status,
        List<String> writtenIds,
        List<String> candidateIds,
        List<String> decisions,
        List<Object> events) {
    public MemoryWriteResult {
        status = status == null ? "unavailable" : status;
        writtenIds = writtenIds == null ? List.of() : List.copyOf(writtenIds);
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static MemoryWriteResult unavailable() {
        return new MemoryWriteResult("unavailable", List.of(), List.of(), List.of(), List.of());
    }
}
