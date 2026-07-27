package com.meguri.core.bilibili;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Deduplicated BV metadata; content is never summarized unless selected later. */
public record BilibiliBrowserVideo(
        String bvid,
        String title,
        String url,
        @JsonProperty("visit_count") int visitCount,
        @JsonProperty("first_visited_at") String firstVisitedAt,
        @JsonProperty("last_visited_at") String lastVisitedAt,
        List<String> sources,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("author_mid") Long authorMid,
        @JsonProperty("tag_name") String tagName,
        @JsonProperty("main_category") String mainCategory,
        String business,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("progress_seconds") Integer progressSeconds,
        @JsonProperty("completion_rate") Double completionRate,
        @JsonProperty("estimated_watch_seconds") Integer estimatedWatchSeconds,
        @JsonProperty("content_summary_status") String contentSummaryStatus) {
    public BilibiliBrowserVideo {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public BilibiliBrowserVideo(
            String bvid,
            String title,
            String url,
            int visitCount,
            String firstVisitedAt,
            String lastVisitedAt,
            List<String> sources) {
        this(bvid, title, url, visitCount, firstVisitedAt, lastVisitedAt, sources,
                null, null, null, null, null, null, null, null, null, "not_requested");
    }
}
