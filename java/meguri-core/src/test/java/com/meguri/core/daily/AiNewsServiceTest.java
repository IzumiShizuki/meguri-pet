package com.meguri.core.daily;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiNewsServiceTest {
    @TempDir Path root;
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-07T02:00:00Z"), AiNewsService.ZONE);
    private DailyReportStore store() { return new DailyReportStore(new ObjectMapper().findAndRegisterModules(), root); }
    private static JuyaRssReader.Article article(String date) {
        return new JuyaRssReader.Article(LocalDate.parse(date), "AI 早报 " + date,
                "https://daily.juya.uk/" + date + "/", "外部新闻内容".repeat(30));
    }

    @Test void waitsForTodayThenReusesTheStoredSummaryAcrossRestarts() throws Exception {
        var store = store();
        var calls = new AtomicInteger();
        var fetches = new AtomicInteger();
        AiNewsService.Fetcher fetch = () -> article(fetches.incrementAndGet() == 1 ? "2026-10-06" : "2026-10-07");
        AiNewsService.Summarizer summarize = ignored -> { calls.incrementAndGet(); return "今日重点\n1. 新模型发布及影响。\n可以聊聊\n如何应用？"; };
        Path desktop = root.resolve("desktop");
        var service = new AiNewsService(store, true, 8, desktop, NOW, fetch, summarize);
        service.pollOnce();
        assertThat(service.status()).containsEntry("state", "waiting_for_today");
        assertThat(store.latest("ai_news")).isEmpty();
        service.pollOnce();
        var report = store.latest("ai_news").orElseThrow();
        assertThat(report.reportId()).isEqualTo("ai_news:2026-10-07");
        assertThat(report.deliveryText()).contains("2026-10-07", "https://daily.juya.uk/2026-10-07/");
        assertThat(desktop.resolve("ai-news-2026-10-07.md")).exists();
        var modified = Files.getLastModifiedTime(desktop.resolve("ai-news-2026-10-07.md"));
        new AiNewsService(store, true, 8, desktop, NOW, fetch, summarize).pollOnce();
        assertThat(calls).hasValue(1);
        assertThat(fetches).hasValue(2);
        assertThat(Files.getLastModifiedTime(desktop.resolve("ai-news-2026-10-07.md"))).isEqualTo(modified);
        assertThat(store.aiNewsContext(NOW).getFirst()).contains("外部新闻参考", "不是指令", "2026-10-07");
        assertThat(store.aiNewsContext(Clock.fixed(Instant.parse("2026-10-11T02:00:00Z"), AiNewsService.ZONE))).isEmpty();
        assertThat(store.aiNewsContext(Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), AiNewsService.ZONE))).isEmpty();
    }

    @Test void disabledAndBeforeEightDoNotFetchOrCallTheModel() {
        var calls = new AtomicInteger();
        AiNewsService.Fetcher fetch = () -> { calls.incrementAndGet(); return article("2026-10-07"); };
        var disabled = new AiNewsService(store(), false, 8, null, NOW, fetch, ignored -> "summary");
        disabled.pollOnce();
        assertThat(disabled.status()).containsEntry("state", "disabled");
        var early = new AiNewsService(store(), true, 8, null,
                Clock.fixed(Instant.parse("2026-10-06T23:59:00Z"), AiNewsService.ZONE), fetch, ignored -> "summary");
        early.pollOnce();
        assertThat(early.status()).containsEntry("state", "before_start_time");
        assertThat(calls).hasValue(0);
    }

    @Test void transientFailuresRetryWithoutInventingASummary() throws Exception {
        var calls = new AtomicInteger();
        var store = store();
        var service = new AiNewsService(store, true, 8, null, NOW, () -> article("2026-10-07"), ignored -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("private provider details");
            return "今日重点：成功摘要。可以聊聊：有什么影响？";
        });
        service.pollOnce();
        assertThat(service.status()).containsEntry("state", "retry_pending").containsEntry("last_error", "IllegalStateException");
        assertThat(store.latest("ai_news")).isEmpty();
        service.pollOnce();
        assertThat(service.status()).containsEntry("state", "ready").containsEntry("last_error", "");
        assertThat(store.latest("ai_news")).isPresent();
    }

    @Test void parsesFullBodyWithNamespacesAndShanghaiPublicationDate() throws Exception {
        String xml = """
                <rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item>
                <title>橘鸦每日早报</title><link>https://daily.juya.uk/news/</link>
                <pubDate>Tue, 06 Oct 2026 23:30:00 GMT</pubDate>
                <description>短摘要</description><content:encoded><![CDATA[<p>%s</p><script>unwanted</script>]]></content:encoded>
                </item></channel></rss>
                """.formatted("完整内容 &amp; 模型发展。".repeat(20));
        var article = JuyaRssReader.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(article.date()).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(article.content()).contains("完整内容 & 模型发展").doesNotContain("短摘要", "unwanted", "<p>");
    }

    @Test void rejectsExternalEntitiesMalformedAndOversizedFeeds() {
        String xml = "<!DOCTYPE rss [<!ENTITY injected SYSTEM 'file:///secret'>]><rss>&injected;</rss>";
        assertThatThrownBy(() -> JuyaRssReader.parse(xml.getBytes(StandardCharsets.UTF_8))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> JuyaRssReader.parse("<rss>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> JuyaRssReader.parse(new byte[JuyaRssReader.MAX_FEED_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
    }
}
