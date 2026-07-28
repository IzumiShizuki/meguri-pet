package com.meguri.core.retrieval;

import java.util.List;

/** Knowledge-store port for deterministic mention/alias normalization. */
@FunctionalInterface
public interface EntityAliasResolverPort {
    List<EntityResolution> resolve(
            String query, List<String> entityMentions, RetrievalContext context);

    record EntityResolution(String mention, String entityId, double confidence) {
        public EntityResolution {
            if (mention == null || mention.isBlank() || entityId == null || entityId.isBlank()) {
                throw new IllegalArgumentException("mention and entityId are required");
            }
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }
    }
}
