package com.meguri.core.memory;

import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Nightly consolidation persists a bounded, redacted session summary.
 *
 * <p>It is deliberately separate from canonical Lore RAG and from automatic
 * model training. Durable facts still use the existing candidate-review path.</p>
 */
@Service
public final class SleepMemoryConsolidationService {
    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(?i)(password|passphrase|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|cookie|private[_ -]?key|银行卡|身份证|密码|令牌|私钥)");

    private final Supplier<List<SessionContextStore.Snapshot>> snapshots;
    private final MemoryGateway memory;
    private final LlmProvider llm;
    private final boolean enabled;
    private final boolean writeCandidates;
    private final ZoneId zone;
    private final int hour;
    private final int minMessages;
    private final Clock clock;
    private final AtomicReference<LocalDate> lastScheduledDate = new AtomicReference<>();
    private final AtomicReference<SleepMemoryReport> lastReport = new AtomicReference<>();

    @Autowired
    public SleepMemoryConsolidationService(
            TurnOrchestrator orchestrator,
            MemoryGateway memory,
            LlmProvider llm,
            @Value("${meguri.sleep-memory.enabled:false}") boolean enabled,
            @Value("${meguri.sleep-memory.timezone:Asia/Shanghai}") String timezone,
            @Value("${meguri.sleep-memory.hour:2}") int hour,
            @Value("${meguri.sleep-memory.min-messages:4}") int minMessages,
            @Value("${meguri.sleep-memory.write-candidates:false}") boolean writeCandidates) {
        this(orchestrator::sessionSnapshots, memory, llm, enabled, ZoneId.of(timezone), hour, minMessages, Clock.systemUTC(), writeCandidates);
    }

    SleepMemoryConsolidationService(
            Supplier<List<SessionContextStore.Snapshot>> snapshots,
            MemoryGateway memory,
            LlmProvider llm,
            boolean enabled,
            ZoneId zone,
            int hour,
            int minMessages,
            Clock clock,
            boolean writeCandidates) {
        this.snapshots = snapshots;
        this.memory = memory;
        this.llm = llm;
        this.writeCandidates = writeCandidates;
        this.enabled = enabled;
        this.zone = zone;
        this.hour = Math.max(0, Math.min(23, hour));
        this.minMessages = Math.max(2, Math.min(20, minMessages));
        this.clock = clock;
    }

    public SleepMemoryReport lastReport() { return lastReport.get(); }

    /** Can be invoked explicitly for verification; automatic scheduling is separately opt-in. */
    public Mono<SleepMemoryReport> consolidateNow() {
        OffsetDateTime ranAt = OffsetDateTime.now(clock.withZone(zone));
        List<SessionContextStore.Snapshot> eligible = snapshots.get().stream()
                .filter(snapshot -> snapshot.messages().size() >= minMessages)
                .toList();
        if (eligible.isEmpty()) {
            SleepMemoryReport report = SleepMemoryReport.empty(ranAt);
            lastReport.set(report);
            return Mono.just(report);
        }
        return Flux.fromIterable(eligible)
                .concatMap(snapshot -> {
                    RedactedSnapshot safe = redact(snapshot);
                    List<String> extractionInput = safe.snapshot().messages().stream()
                            .map(message -> message.role() + ": " + message.content())
                            .toList();
                    return llm.extractMemoryCandidates(extractionInput)
                            .onErrorReturn(List.of())
                            .map(candidates -> candidates == null ? List.<MemoryCandidate>of() : candidates.stream()
                                    .limit(3)
                                    .toList())
                            .flatMap(candidates -> memory.summarize(new SessionSummaryRequest(
                                    safe.snapshot().userId(), safe.snapshot().clientId(), safe.snapshot().sessionId(),
                                    safe.snapshot().messages(), candidates, writeCandidates))
                            .onErrorReturn(SessionSummaryResult.unavailable(
                                    safe.snapshot().userId(), safe.snapshot().clientId(), safe.snapshot().sessionId()))
                            .map(result -> new ConsolidationResult(result, safe.redactedMessages(), candidates.size())));
                })
                .collectList()
                .map(results -> {
                    int persisted = (int) results.stream().filter(result -> "persisted".equals(result.result().status())).count();
                    int redacted = results.stream().mapToInt(ConsolidationResult::redactedMessages).sum();
                    int candidates = results.stream().mapToInt(ConsolidationResult::candidateCount).sum();
                    int queued = results.stream().mapToInt(result -> result.result().candidateIds().size()).sum();
                    SleepMemoryReport report = new SleepMemoryReport(ranAt, eligible.size(), persisted,
                            eligible.size() - persisted, redacted, candidates, queued);
                    lastReport.set(report);
                    return report;
                });
    }

    /** Runs at most once in the configured local 02:00 hour while the Java Core is alive. */
    @Scheduled(fixedDelayString = "${meguri.sleep-memory.check-delay-ms:60000}")
    public void consolidateDuringSleep() {
        if (!enabled) return;
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        if (now.getHour() != hour || today.equals(lastScheduledDate.get())) return;
        consolidateNow().doOnSuccess(ignored -> lastScheduledDate.set(today)).subscribe(ignored -> { }, error -> { });
    }

    private static RedactedSnapshot redact(SessionContextStore.Snapshot snapshot) {
        int redacted = 0;
        List<SessionContextStore.Message> messages = new java.util.ArrayList<>();
        for (SessionContextStore.Message message : snapshot.messages()) {
            String content = message.content() == null ? "" : message.content();
            if (CREDENTIAL_LIKE.matcher(content).find()) {
                messages.add(new SessionContextStore.Message(message.role(), "[已省略可能包含敏感凭据的消息]"));
                redacted++;
            } else {
                messages.add(message);
            }
        }
        return new RedactedSnapshot(new SessionContextStore.Snapshot(snapshot.userId(), snapshot.clientId(),
                snapshot.sessionId(), List.copyOf(messages)), redacted);
    }

    private record RedactedSnapshot(SessionContextStore.Snapshot snapshot, int redactedMessages) { }
    private record ConsolidationResult(SessionSummaryResult result, int redactedMessages, int candidateCount) { }
}
