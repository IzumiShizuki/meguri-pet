package com.meguri.core.skill;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SkillSourcePlugin {
    String id();
    SearchPage search(String query, int page, int pageSize);
    Detail detail(String externalId);
    FetchResult fetch(String externalId);
    default void release(FetchResult result) { }
    UpdateStatus checkUpdate(String externalId, String currentRevision);

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Summary(String externalId, String name, String description, String license,
                   List<String> tags, String revision,
                   @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt) {
        public Summary {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SearchPage(List<Summary> items, int page, int pageSize, long total) {
        public SearchPage { items = items == null ? List.of() : List.copyOf(items); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Detail(Summary summary, SkillRequirement requirements, Map<String, Object> metadata) {
        public Detail {
            requirements = requirements == null ? SkillRequirement.none() : requirements;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record FetchResult(String externalId, String revision, Path stagingPath, String fetchId) {
        public FetchResult(String externalId, String revision, Path stagingPath) {
            this(externalId, revision, stagingPath, null);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record UpdateStatus(boolean updateAvailable, String currentRevision, String latestRevision) { }

    final class SourceException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public SourceException(String code, String message, boolean retryable) {
            super(message); this.code = code; this.retryable = retryable;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
