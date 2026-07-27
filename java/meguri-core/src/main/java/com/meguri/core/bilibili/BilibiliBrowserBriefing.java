package com.meguri.core.bilibili;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Metadata-only daily result from account MCP, or an explicit browser-history fallback. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BilibiliBrowserBriefing(
        String status,
        String date,
        @JsonProperty("generated_at") String generatedAt,
        @JsonProperty("data_source") String dataSource,
        @JsonProperty("sync_status") String syncStatus,
        @JsonProperty("unique_videos") int uniqueVideos,
        @JsonProperty("total_visits") int totalVisits,
        @JsonProperty("first_visited_at") String firstVisitedAt,
        @JsonProperty("last_visited_at") String lastVisitedAt,
        String summary,
        List<BilibiliBrowserVideo> videos,
        List<BilibiliBrowserSource> sources,
        String boundary,
        List<BilibiliBrowserArtifact> artifacts,
        String error) {

    public static final String BROWSER_PRIVACY_BOUNDARY =
            "本报告仅统计本机 Chrome/Edge 浏览器 History 中的 Bilibili 视频页面访问；"
                    + "页面访问不代表视频已播放、看完，也无法推断实际观看时长；"
                    + "不包含无痕窗口、其他设备或 Bilibili App。";
    public static final String ACCOUNT_MCP_PRIVACY_BOUNDARY =
            "本报告只通过 BilibiliHistoryFetcher 的本机只读 MCP 获取已同步账号观看历史元数据；"
                    + "Meguri 不读取 Cookie 或原始账号库、不会直接调用 Bilibili 账号接口，也不下载页面、字幕或视频；"
                    + "结果仅覆盖 MCP 当前返回且账号接口仍可见的记录，可能存在同步延迟、缺失或进度误差；"
                    + "时长与完成率均为元数据估算，不代表精确观看行为。";
    /** Kept as a compatibility alias for existing browser-fallback assertions. */
    public static final String PRIVACY_BOUNDARY = BROWSER_PRIVACY_BOUNDARY;

    public BilibiliBrowserBriefing {
        videos = videos == null ? List.of() : List.copyOf(videos);
        sources = sources == null ? List.of() : List.copyOf(sources);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static BilibiliBrowserBriefing unavailable(String date, String error) {
        return new BilibiliBrowserBriefing(
                "unavailable", date, null, "unavailable", "unavailable", 0, 0, null, null, null,
                List.of(), List.of(), BROWSER_PRIVACY_BOUNDARY, List.of(), error);
    }
}
