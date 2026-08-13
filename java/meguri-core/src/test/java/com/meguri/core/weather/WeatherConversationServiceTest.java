package com.meguri.core.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.MemorySensitivity;
import com.meguri.core.dto.MemorySourceScope;
import com.meguri.core.dto.MemoryType;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.dto.VoiceStyle;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.runtime.ExpressionResolver;
import com.meguri.core.runtime.RuntimeStateMachine;
import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherConversationServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final WeatherLocation QIANTANG =
            new WeatherLocation("浙江省杭州市钱塘区", 30.323040, 120.493941, "Asia/Shanghai");

    @TempDir Path tempDir;

    @Test
    void outingIntentAlwaysFetchesWeatherAndAddsCaringRoadSafetyGreeting() {
        AtomicInteger calls = new AtomicInteger();
        WeatherService weather = weather(location -> {
            calls.incrementAndGet();
            return Mono.just(briefing(location));
        });
        WeatherConversationService conversation = new WeatherConversationService(weather);

        var context = conversation.contextFor("我要出门了").block();
        LlmResponse response = context.augment(new LlmResponse("早点回来。"));

        assertThat(calls).hasValue(1);
        assertThat(context.intent()).isEqualTo(WeatherConversationService.Intent.OUTING);
        assertThat(response.reply()).contains("要出门了吗").contains("钱塘区目前多云")
                .contains("路上注意安全").contains("早点回来");
    }

    @Test
    void weatherAndOutingTurnsExplicitlyReportAProviderFailure() {
        WeatherService weather = weather(location -> Mono.error(new IllegalStateException("offline")));
        WeatherConversationService conversation = new WeatherConversationService(weather);

        LlmResponse weatherReply = conversation.contextFor("今天湿度和风速怎么样？").block()
                .augment(new LlmResponse("稍后再试。"));
        LlmResponse outingReply = conversation.contextFor("准备出门一下").block()
                .augment(new LlmResponse("好。"));

        assertThat(weatherReply.reply()).contains("实时天气暂时不可用");
        assertThat(outingReply.reply()).contains("实时天气暂时没查到").contains("路上注意安全");
    }

    @Test
    void ordinaryConversationDoesNotContactWeatherProvider() {
        AtomicInteger calls = new AtomicInteger();
        WeatherService weather = weather(location -> {
            calls.incrementAndGet();
            return Mono.just(briefing(location));
        });
        WeatherConversationService conversation = new WeatherConversationService(weather);

        var context = conversation.contextFor("帮我看看这段代码").block();

        assertThat(context.intent()).isEqualTo(WeatherConversationService.Intent.NONE);
        assertThat(calls).hasValue(0);
    }

    @Test
    void tomorrowWeatherMessageRequestsTomorrowsForecast() {
        AtomicReference<LocalDate> requestedDate = new AtomicReference<>();
        WeatherGateway gateway = new WeatherGateway() {
            @Override
            public Mono<WeatherBriefing> fetch(WeatherLocation location) {
                return Mono.error(new AssertionError("dated forecast should be used"));
            }

            @Override
            public Mono<WeatherBriefing> fetch(WeatherLocation location, LocalDate targetDate) {
                requestedDate.set(targetDate);
                return Mono.just(briefing(location));
            }
        };
        WeatherService weather = new WeatherService(
                gateway, MAPPER, true, tempDir.resolve("tomorrow-weather-location.json"),
                QIANTANG, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                8, 18);
        WeatherConversationService conversation = new WeatherConversationService(weather);

        var context = conversation.contextFor("明天天气怎么样？").block();
        LlmResponse response = context.directResponse("zh_ja_pairs");

        assertThat(requestedDate).hasValue(LocalDate.parse("2026-07-22"));
        assertThat(response.reply()).contains("钱塘区目前多云").contains("の予報は");
        assertThat(response.expressionTag()).isEqualTo(ExpressionTag.NEUTRAL);
    }

    @Test
    void narrowFollowUpInheritsWeatherIntentAndTomorrowDateFromActivePath() {
        AtomicReference<LocalDate> requestedDate = new AtomicReference<>();
        WeatherGateway gateway = new WeatherGateway() {
            @Override
            public Mono<WeatherBriefing> fetch(WeatherLocation location) {
                return Mono.error(new AssertionError("dated forecast should be used"));
            }

            @Override
            public Mono<WeatherBriefing> fetch(WeatherLocation location, LocalDate targetDate) {
                requestedDate.set(targetDate);
                return Mono.just(briefing(location));
            }
        };
        WeatherService weather = new WeatherService(
                gateway, MAPPER, true, tempDir.resolve("followup-weather-location.json"),
                QIANTANG, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                8, 18);
        WeatherConversationService conversation = new WeatherConversationService(weather);
        var resolution = conversation.resolveTurn(
                "我问的是明天来着",
                List.of(
                        new SessionContextStore.Message("user", "明天天气怎么样？"),
                        new SessionContextStore.Message("assistant", "会有阵雨。"),
                        new SessionContextStore.Message("user", "我问的是明天来着")));

        var context = conversation.contextFor(resolution).block();

        assertThat(resolution.intent()).isEqualTo(WeatherConversationService.Intent.WEATHER);
        assertThat(resolution.inherited()).isTrue();
        assertThat(requestedDate).hasValue(LocalDate.parse("2026-07-22"));
        assertThat(context.available()).isTrue();
    }

    @Test
    void explicitFollowUpDateOverridesHistoryAndUnrelatedBranchesStayIsolated() {
        WeatherConversationService conversation = new WeatherConversationService(
                weather(location -> Mono.just(briefing(location))));
        List<SessionContextStore.Message> activeWeatherPath = List.of(
                new SessionContextStore.Message("user", "明天天气怎么样？"),
                new SessionContextStore.Message("assistant", "会有阵雨。"));

        var override = conversation.resolveTurn("那后天呢", activeWeatherPath);
        var isolated = conversation.resolveTurn("我问的是明天来着", List.of(
                new SessionContextStore.Message("user", "帮我看一下代码"),
                new SessionContextStore.Message("assistant", "好的")));

        assertThat(override.intent()).isEqualTo(WeatherConversationService.Intent.WEATHER);
        assertThat(override.dateSource()).isEqualTo("那后天呢");
        assertThat(isolated.intent()).isEqualTo(WeatherConversationService.Intent.NONE);
    }

    @Test
    void commonFirstPersonDeparturePhrasesAreRecognizedWithoutMatchingPlanningDiscussion() {
        WeatherConversationService conversation = new WeatherConversationService(
                weather(location -> Mono.just(briefing(location))));

        assertThat(List.of("我出发了", "我要去公司了", "出去玩", "去吃饭了", "我马上回家了"))
                .allSatisfy(message -> assertThat(conversation.classify(message))
                        .as(message).isEqualTo(WeatherConversationService.Intent.OUTING));
        assertThat(conversation.classify("我们讨论一下什么时候出发"))
                .isEqualTo(WeatherConversationService.Intent.NONE);
    }

    @Test
    void transientToolWeatherFactsCannotBecomeLongTermMemoryCandidates() {
        WeatherConversationService conversation = new WeatherConversationService(
                weather(location -> Mono.just(briefing(location))));
        var context = conversation.contextFor("天气怎么样？").block();
        LlmResponse raw = new LlmResponse("知道了。", ExpressionTag.NEUTRAL, Intensity.LOW,
                VoiceStyle.NEUTRAL, List.of(
                new MemoryCandidate(MemoryType.EVENT, "钱塘区目前多云，湿度72%", 0.9,
                        MemorySensitivity.NORMAL, MemorySourceScope.CURRENT_MESSAGE),
                new MemoryCandidate(MemoryType.PREFERENCE, "用户习惯出门前查看天气", 0.9,
                        MemorySensitivity.NORMAL, MemorySourceScope.CURRENT_MESSAGE)));

        LlmResponse augmented = context.augment(raw);

        assertThat(augmented.memoryCandidates()).extracting(MemoryCandidate::summary)
                .containsExactly("用户习惯出门前查看天气");
    }

    @Test
    void sampledCandidatesDisplayAndSessionShareTheSameSingleWeatherSnapshot() {
        AtomicInteger weatherCalls = new AtomicInteger();
        WeatherService weather = weather(location -> {
            weatherCalls.incrementAndGet();
            return Mono.just(briefing(location));
        });
        AtomicInteger llmCalls = new AtomicInteger();
        LlmProvider llm = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                              List<String> canon, List<String> memories,
                                              List<String> recentContext) {
                String reply = llmCalls.getAndIncrement() == 0 ? "早点回来。" : "记得看路。";
                return Mono.just(new LlmResponse(reply, ExpressionTag.WORRIED, Intensity.LOW,
                        VoiceStyle.SOFT, List.of()));
            }
        };
        TrainingFeedbackService feedback = new TrainingFeedbackService(
                MAPPER, tempDir.resolve("weather-feedback.jsonl"), 1, 0, () -> 0);
        TurnOrchestrator orchestrator = new TurnOrchestrator(
                llm, (query, state, limit) -> List.of(), new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ZERO, MAPPER, new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback,
                new WeatherConversationService(weather));
        TurnRequest request = new TurnRequest(
                "user-a", "desktop_pet", "weather-session", null, "我要出门了",
                List.of(), new ClientCapabilities(), null, null, false, true);

        var result = orchestrator.runInline(request).block();
        var comparison = orchestrator.eventsFor("weather-session").stream()
                .filter(event -> "training.candidates.ready".equals(event.type())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) comparison.data().get("candidates");
        List<LlmResponse> candidateResponses = candidates.stream()
                .map(candidate -> (LlmResponse) candidate.get("response")).toList();

        assertThat(weatherCalls).hasValue(1);
        assertThat(candidateResponses).allSatisfy(response ->
                assertThat(response.reply()).contains("钱塘区目前多云").contains("路上注意安全"));
        assertThat(result.response()).isEqualTo(candidateResponses.getFirst());
        assertThat(orchestrator.sessionMessages("user-a", "desktop_pet", "weather-session").getLast().content())
                .isEqualTo(result.response().reply());
    }

    @Test
    void fastWeatherTurnUsesTheWeatherCapabilityWithoutCallingTheLlm() {
        AtomicInteger weatherCalls = new AtomicInteger();
        WeatherService weather = weather(location -> {
            weatherCalls.incrementAndGet();
            return Mono.just(briefing(location));
        });
        AtomicInteger llmCalls = new AtomicInteger();
        LlmProvider llm = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                              List<String> canon, List<String> memories,
                                              List<String> recentContext) {
                llmCalls.incrementAndGet();
                return Mono.error(new AssertionError("explicit weather should not call the LLM"));
            }
        };
        TurnOrchestrator orchestrator = new TurnOrchestrator(
                llm, (query, state, limit) -> List.of(), new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ZERO, MAPPER, new NoopMemoryGateway(), new NoopWebSearchGateway(),
                new TrainingFeedbackService(MAPPER, tempDir.resolve("fast-weather-feedback.jsonl"),
                        1, 0, () -> 0),
                new WeatherConversationService(weather));
        TurnRequest request = new TurnRequest(
                "user-a", "desktop_pet", "fast-weather-session", null, "今天天气怎么样？",
                List.of(), new ClientCapabilities(), null, null, false, false,
                "zh_ja_pairs", RetrievalMode.FAST);

        var result = orchestrator.runInline(request).block();

        assertThat(weatherCalls).hasValue(1);
        assertThat(llmCalls).hasValue(0);
        assertThat(result.response().reply()).contains("钱塘区目前多云");
        assertThat(result.response().expressionTag()).isEqualTo(ExpressionTag.NEUTRAL);
        assertThat(orchestrator.eventsFor("fast-weather-session"))
                .extracting(event -> event.type())
                .contains("tool.started", "tool.completed");
    }

    private WeatherService weather(WeatherGateway gateway) {
        return new WeatherService(gateway, MAPPER, true, tempDir.resolve("weather-location.json"),
                QIANTANG, Clock.systemUTC(), 8, 18);
    }

    private static WeatherBriefing briefing(WeatherLocation location) {
        return new WeatherBriefing(location, OffsetDateTime.parse("2026-07-22T10:00:00+08:00"),
                "2026-07-22", "多云", 30.2, 72, 13.5,
                27, 34, 35, false, null, null,
                location.name() + "目前多云；当前30.2℃，湿度72%，风速13.5公里/小时。");
    }
}
