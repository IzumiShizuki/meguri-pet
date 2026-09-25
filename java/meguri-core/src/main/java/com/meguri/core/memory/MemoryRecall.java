package com.meguri.core.memory;

import java.util.List;

public record MemoryRecall(List<String> memories, boolean available) {
    public MemoryRecall {
        memories = memories == null ? List.of() : List.copyOf(memories);
    }

    public static MemoryRecall unavailable() {
        return new MemoryRecall(List.of(), false);
    }

    public static MemoryRecall available(List<String> memories) {
        return new MemoryRecall(memories, true);
    }
}
