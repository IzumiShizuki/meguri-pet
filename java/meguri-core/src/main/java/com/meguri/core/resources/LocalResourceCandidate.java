package com.meguri.core.resources;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Search-result metadata only. This contract deliberately has no content,
 * download, open or execute field.
 */
public record LocalResourceCandidate(
        String id,
        String name,
        String path,
        String kind,
        @JsonProperty("size_bytes") Long sizeBytes,
        @JsonProperty("modified_at") String modifiedAt) {
}
