package com.meguri.core.bilibili;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Read status for one Chrome or Edge profile History database. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BilibiliBrowserSource(
        String name,
        String kind,
        String status,
        @JsonProperty("visit_count") int visitCount,
        String message) {
    public BilibiliBrowserSource(String name, String status, int visitCount, String message) {
        this(name, null, status, visitCount, message);
    }
}
