package com.meguri.core.knowledge.notion;

import com.meguri.core.knowledge.IngestionOutcome;
import com.meguri.core.knowledge.IngestionResult;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record NotionSyncReport(
        String sourceId,
        Instant completedAt,
        int pages,
        Map<IngestionOutcome, Long> outcomes) {

    static NotionSyncReport from(String sourceId, Instant completedAt, List<IngestionResult> results) {
        EnumMap<IngestionOutcome, Long> counts = new EnumMap<>(IngestionOutcome.class);
        for (IngestionResult result : results) counts.merge(result.outcome(), 1L, Long::sum);
        return new NotionSyncReport(sourceId, completedAt, results.size(), Map.copyOf(counts));
    }
}
