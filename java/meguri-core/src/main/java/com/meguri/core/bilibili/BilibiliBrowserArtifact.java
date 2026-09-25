package com.meguri.core.bilibili;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A generated report link plus its local path for desktop clients. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BilibiliBrowserArtifact(
        String label,
        String href,
        @JsonProperty("local_path") String localPath) { }
