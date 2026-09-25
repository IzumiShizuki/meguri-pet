package com.meguri.core.bilibili;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Result contract that cannot imply a summary was produced when content access is unavailable. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BilibiliSelectedVideoSummaryResult(
        String status,
        @JsonProperty("selected_bvids") List<String> selectedBvids,
        String summary,
        String boundary,
        String error,
        @JsonProperty("next_step") String nextStep) {
    public BilibiliSelectedVideoSummaryResult {
        selectedBvids = selectedBvids == null ? List.of() : List.copyOf(selectedBvids);
    }

    public static BilibiliSelectedVideoSummaryResult unavailable(List<String> selectedBvids) {
        return new BilibiliSelectedVideoSummaryResult(
                "unavailable",
                selectedBvids,
                null,
                "只有 selected_bvids 中由用户明确选择的视频才允许进入内容总结；日报中的其他视频不得自动读取内容。",
                "当前阶段仅接入观看历史元数据，未接入字幕或用户提供资源的只读内容源。",
                "后续接入受控内容源后，再由用户对选中的 BV 号重新发起总结。"
        );
    }
}
