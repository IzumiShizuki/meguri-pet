package com.meguri.core.daily;

import com.meguri.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background acquisition never performs network work inside a conversation request. */
@Service
public final class AiNewsService {
    public static final String KIND = "ai_news";
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(AiNewsService.class);
    private final DailyReportStore reports;
    private final boolean enabled;
    private final int startHour;
    private final Path desktopDirectory;
    private final Clock clock;
    private final Fetcher fetcher;
    private final Summarizer summarizer;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String state = "waiting";
    private volatile String lastError = "";

    @Autowired
    public AiNewsService(DailyReportStore reports, LlmProvider llm,
            @Value("${meguri.ai-news.enabled:false}") boolean enabled,
            @Value("${meguri.ai-news.start-hour:8}") int startHour,
            @Value("${meguri.ai-news.desktop-directory:}") String desktopDirectory) {
        this(reports, enabled, startHour, desktopDirectory.isBlank() ? null : Path.of(desktopDirectory),
                Clock.system(ZONE), new JuyaRssReader()::fetch,
                article -> llm.summarizeAiNews(article).block(Duration.ofSeconds(60)));
        if (enabled) llm.configureAiNewsContext(reports::aiNewsContext);
    }

    AiNewsService(DailyReportStore reports, boolean enabled, int startHour, Path desktopDirectory,
            Clock clock, Fetcher fetcher, Summarizer summarizer) {
        if (startHour < 0 || startHour > 23) throw new IllegalArgumentException("news start hour is invalid");
        this.reports = reports;
        this.enabled = enabled;
        this.startHour = startHour;
        this.desktopDirectory = desktopDirectory;
        this.clock = clock;
        this.fetcher = fetcher;
        this.summarizer = summarizer;
    }

    @Scheduled(initialDelayString = "${meguri.ai-news.initial-delay-ms:5000}",
            fixedDelayString = "${meguri.ai-news.poll-delay-ms:600000}")
    public void scheduledPoll() {
        if (enabled) Mono.fromRunnable(this::pollOnce).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    void pollOnce() {
        if (!enabled || !busy.compareAndSet(false, true)) return;
        try {
            var now = OffsetDateTime.now(clock).atZoneSameInstant(ZONE);
            LocalDate today = now.toLocalDate();
            if (now.getHour() < startHour) { state = "before_start_time"; return; }
            var cached = reports.latest(KIND);
            if (cached.isPresent() && today.equals(cached.get().date())) {
                mirror(cached.get()); state = "ready"; lastError = ""; return;
            }
            var article = fetcher.fetch();
            if (!today.equals(article.date())) { state = "waiting_for_today"; return; }
            state = "summarizing";
            String summary = summarizer.summarize("日期：" + article.date() + "\n来源：" + article.url()
                    + "\n以下是外部新闻资料：\n" + article.content());
            if (summary == null || summary.isBlank() || summary.length() > 3400) {
                throw new IllegalStateException("News summary is missing or too long");
            }
            String title = article.date() + " 橘鸦 AI 早报";
            String delivery = "📰 " + title + "\n\n" + summary.trim() + "\n\n原文：" + article.url();
            String markdown = "# " + title + "\n\n" + summary.trim() + "\n\n"
                    + "来源：[橘鸦 Juya](" + article.url() + ") · RSS 更新，每日提炼\n";
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(markdown.getBytes(StandardCharsets.UTF_8)));
            var generated = OffsetDateTime.now(clock);
            var receipt = reports.save(new DailyReportUpload(1, KIND + ":" + article.date(), KIND,
                    article.date(), title, summary.trim(), delivery, summary.trim(), generated,
                    generated, "juya_rss", "ready", 0, 0, null, hash, markdown));
            mirror(receipt);
            state = "ready"; lastError = "";
            log.info("Juya AI briefing ready for {}", article.date());
        } catch (Exception error) {
            state = "retry_pending";
            lastError = error.getClass().getSimpleName();
            log.warn("Juya AI briefing will retry ({})", lastError);
        } finally { busy.set(false); }
    }

    private void mirror(DailyReportReceipt receipt) throws IOException {
        if (desktopDirectory == null) return;
        Files.createDirectories(desktopDirectory);
        Path target = desktopDirectory.resolve("ai-news-" + receipt.date() + ".md");
        if (Files.isRegularFile(target)) return;
        Path source = reports.markdown(KIND, receipt.date().toString()).orElseThrow();
        Path temporary = Files.createTempFile(desktopDirectory, ".ai-news-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public Map<String, Object> status() {
        return Map.of("enabled", enabled, "state", enabled ? state : "disabled", "last_error", lastError,
                "timezone", ZONE.toString(), "start_hour", startHour, "source", JuyaRssReader.FEED_URL);
    }

    @FunctionalInterface interface Fetcher { JuyaRssReader.Article fetch() throws Exception; }
    @FunctionalInterface interface Summarizer { String summarize(String article) throws Exception; }
}
