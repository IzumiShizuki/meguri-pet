package com.meguri.core.bilibili;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.regex.Pattern;

/** Explicit user selection contract for a future content-level summary. */
public record BilibiliSelectedVideoSummaryRequest(
        @JsonProperty("selected_bvids") List<String> selectedBvids,
        String instruction) {
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final int MAX_SELECTIONS = 5;
    private static final int MAX_INSTRUCTION_LENGTH = 500;

    public BilibiliSelectedVideoSummaryRequest {
        selectedBvids = selectedBvids == null
                ? List.of()
                : selectedBvids.stream().map(String::trim).distinct().toList();
        if (selectedBvids.isEmpty() || selectedBvids.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("selected_bvids must contain 1 to 5 explicit BV ids");
        }
        if (selectedBvids.stream().anyMatch(value -> !BVID.matcher(value).matches())) {
            throw new IllegalArgumentException("selected_bvids contains an invalid BV id");
        }
        instruction = instruction == null ? "" : instruction.trim();
        if (instruction.length() > MAX_INSTRUCTION_LENGTH) {
            throw new IllegalArgumentException("instruction is too long");
        }
    }
}
