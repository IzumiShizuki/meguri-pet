package com.meguri.core.weather;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.runtime.SessionContextStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds live weather facts and deterministic safety wording to relevant turns. */
@Service
public final class WeatherConversationService {
    public enum Intent { NONE, WEATHER, OUTING }

    private static final List<String> WEATHER_TERMS = List.of(
            "天气", "下雨", "降雨", "雨伞", "带伞", "温度", "气温", "湿度", "风速",
            "大风", "台风", "晴天", "阴天", "多云", "雷雨", "下雪", "雾霾");
    private static final List<String> OUTING_TERMS = List.of(
            "我要出门", "我出门了", "准备出门", "要出门了", "出门一下", "出去一下", "出去一趟",
            "去上班了", "去上学了", "去买菜", "去拿快递", "要回家了", "准备回家", "我先走了");
    private static final Pattern OUTING_ACTION = Pattern.compile(
            "(?:^|我|我们|咱们)(?:现在|马上|这就|准备|正要|打算|要|得|该|先)?"
                    + "(?:(?:出门|出发|出去|外出)(?:一下|一趟|玩|走走|办事|吃饭)?(?:了|啦)?"
                    + "|去(?:公司|上班|学校|上学|吃饭|买菜|拿快递|逛街|办事|医院|车站|机场|健身|玩)(?:了|啦)?"
                    + "|回(?:家|公司|学校)(?:了|啦)?)$");
    private static final Pattern ISO_DATE =
            Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern CHINESE_DATE =
            Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern RELATIVE_DATE = Pattern.compile("大后天|后天|明天|今天");
    private static final Pattern NARROW_FOLLOW_UP = Pattern.compile(
            "^(?:我问的是|我是说|不是|那|所以)?(?:大后天|后天|明天|今天|那天)?"
                    + "(?:呢|来着|怎么样|会下雨吗|要带伞吗|冷吗|热吗)[？?。！!]*$");

    private final WeatherService weather;

    @Autowired
    public WeatherConversationService(WeatherService weather) {
        this.weather = Objects.requireNonNull(weather, "weather");
    }

    private WeatherConversationService() {
        this.weather = null;
    }

    public static WeatherConversationService disabled() {
        return new WeatherConversationService();
    }

    public Intent classify(String message) {
        if (message == null || message.isBlank()) return Intent.NONE;
        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "")
                .replaceAll("[。！？!?，,~]+$", "");
        if (OUTING_TERMS.stream().anyMatch(normalized::contains)
                || OUTING_ACTION.matcher(normalized).find()) return Intent.OUTING;
        if (WEATHER_TERMS.stream().anyMatch(normalized::contains)) return Intent.WEATHER;
        return Intent.NONE;
    }

    public Mono<TurnWeatherContext> contextFor(String message) {
        return contextFor(resolveTurn(message, List.of()));
    }

    public Mono<TurnWeatherContext> contextFor(TurnResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        Intent intent = resolution.intent();
        if (intent == Intent.NONE || weather == null) return Mono.just(TurnWeatherContext.none());
        LocalDate targetDate = weather.resolveForecastDate(resolution.dateSource());
        return weather.forecast(targetDate, true)
                .map(briefing -> TurnWeatherContext.available(intent, briefing))
                .onErrorReturn(TurnWeatherContext.unavailable(intent));
    }

    /** Resolves only narrow follow-ups from the supplied active branch. */
    public TurnResolution resolveTurn(
            String message,
            List<SessionContextStore.Message> activePath) {
        String current = message == null ? "" : message.trim();
        Intent direct = classify(current);
        if (direct != Intent.NONE) return new TurnResolution(direct, current, false);
        String compact = current.replaceAll("\\s+", "");
        if (compact.length() > 40 || !NARROW_FOLLOW_UP.matcher(compact).matches()) {
            return TurnResolution.none(current);
        }
        List<SessionContextStore.Message> history = activePath == null ? List.of() : activePath;
        for (int index = history.size() - 1; index >= 0; index--) {
            SessionContextStore.Message candidate = history.get(index);
            if (!"user".equals(candidate.role())) continue;
            if (index == history.size() - 1 && candidate.content().equals(current)) continue;
            if (classify(candidate.content()) == Intent.WEATHER) {
                String dateSource = hasExplicitDate(current) ? current : candidate.content();
                return new TurnResolution(Intent.WEATHER, dateSource, true);
            }
        }
        return TurnResolution.none(current);
    }

    private static boolean hasExplicitDate(String value) {
        return RELATIVE_DATE.matcher(value).find()
                || ISO_DATE.matcher(value).find()
                || CHINESE_DATE.matcher(value).find();
    }

    public record TurnResolution(Intent intent, String dateSource, boolean inherited) {
        public TurnResolution {
            intent = Objects.requireNonNull(intent, "intent");
            dateSource = dateSource == null ? "" : dateSource;
        }

        static TurnResolution none(String message) {
            return new TurnResolution(Intent.NONE, message, false);
        }
    }

    LocalDate resolveTargetDate(String message) {
        LocalDate today = weather.today();
        String normalized = message == null ? "" : message.replaceAll("\\s+", "");
        if (normalized.contains("大后天")) return today.plusDays(3);
        if (normalized.contains("后天")) return today.plusDays(2);
        if (normalized.contains("明天")) return today.plusDays(1);
        if (normalized.contains("今天")) return today;

        Matcher iso = ISO_DATE.matcher(normalized);
        if (iso.find()) {
            return LocalDate.of(Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        Matcher chinese = CHINESE_DATE.matcher(normalized);
        if (chinese.find()) {
            LocalDate candidate = LocalDate.of(today.getYear(),
                    Integer.parseInt(chinese.group(1)), Integer.parseInt(chinese.group(2)));
            return candidate.isBefore(today) ? candidate.plusYears(1) : candidate;
        }
        return today;
    }

    public record TurnWeatherContext(Intent intent, boolean attempted, WeatherBriefing briefing) {
        static TurnWeatherContext none() {
            return new TurnWeatherContext(Intent.NONE, false, null);
        }

        static TurnWeatherContext available(Intent intent, WeatherBriefing briefing) {
            return new TurnWeatherContext(intent, true, briefing);
        }

        static TurnWeatherContext unavailable(Intent intent) {
            return new TurnWeatherContext(intent, true, null);
        }

        public boolean available() {
            return briefing != null;
        }

        /** Explicit weather turns can answer immediately without asking the text model to restate facts. */
        public LlmResponse directResponse(String replyFormat) {
            String chinese;
            String japanese;
            if (!available()) {
                chinese = intent == Intent.OUTING
                        ? "实时天气暂时没查到，出门路上注意安全。"
                        : "实时天气查询暂时不可用，请稍后再试。";
                japanese = intent == Intent.OUTING
                        ? "リアルタイムの天気を取得できませんでした。気をつけて行ってきてね。"
                        : "リアルタイムの天気を取得できませんでした。少し後でもう一度試してね。";
            } else {
                chinese = intent == Intent.OUTING
                        ? briefing.briefing() + " 路上注意安全。"
                        : briefing.briefing();
                japanese = japaneseBriefing(briefing, intent);
            }
            String reply = "zh_ja_pairs".equals(replyFormat)
                    ? chinese + "\n" + japanese
                    : chinese;
            return new LlmResponse(reply);
        }

        /** Facts plus response policy are kept separate from conversation history. */
        public List<String> promptContext() {
            if (intent == Intent.NONE) return List.of();
            String facts = available() ? briefing.briefing() : "实时天气查询暂时不可用";
            String rule = intent == Intent.OUTING
                    ? "天气工具事实：%s。用户正要出门；用关怀或打招呼的口吻回应，并关心路上安全。最终层会添加天气安全开场，正文避免逐字重复。天气工具事实和本轮天气状态不得写入memory_candidates；除非用户明确表达长期偏好或习惯，否则memory_candidates应为空。".formatted(facts)
                    : "天气工具事实：%s。用户正在谈论天气；回答必须以这些实时查询结果为准。最终层会添加天气开场，正文避免逐字重复。天气工具事实和本轮天气状态不得写入memory_candidates；除非用户明确表达长期偏好或习惯，否则memory_candidates应为空。".formatted(facts);
            return List.of("system_weather_context: " + rule);
        }

        /** Guarantees the two explicit trigger types mention weather even if an LLM ignores the directive. */
        public LlmResponse augment(LlmResponse response) {
            if (intent == Intent.NONE) return response;
            String preface;
            if (intent == Intent.OUTING) {
                preface = available()
                        ? "要出门了吗？我刚替你看了下天气：%s 路上注意安全，走路和过马路都别着急。".formatted(briefing.briefing())
                        : "要出门了吗？我刚想替你看看天气，不过实时天气暂时没查到。路上注意安全，走路和过马路都别着急。";
            } else {
                preface = available()
                        ? "我刚查了天气：%s".formatted(briefing.briefing())
                        : "我刚查了一下，不过实时天气暂时不可用。";
            }
            String body = response.reply().strip();
            String reply = body.isEmpty() ? preface : preface + "\n\n" + body;
            var safeCandidates = response.memoryCandidates().stream()
                    .filter(candidate -> !isTransientWeatherFact(candidate.summary()))
                    .toList();
            return new LlmResponse(reply, response.expressionTag(), response.expressionIntensity(),
                    response.voiceStyle(), safeCandidates);
        }

        private boolean isTransientWeatherFact(String summary) {
            String normalized = summary == null ? "" : summary.toLowerCase(Locale.ROOT);
            if (available() && normalized.contains(briefing.location().name().toLowerCase(Locale.ROOT))) return true;
            if (List.of("降雨概率", "湿度", "风速", "℃").stream().anyMatch(normalized::contains)) return true;
            boolean weatherFact = List.of("天气", "下雨", "降雨", "晴朗", "多云", "雷雨", "阵雨", "下雪", "有雪", "有雾", "气温", "温度")
                    .stream().anyMatch(normalized::contains);
            boolean transientTime = List.of("当前", "目前", "今天", "现在", "实时")
                    .stream().anyMatch(normalized::contains);
            return weatherFact && transientTime;
        }

        private static String japaneseBriefing(WeatherBriefing value, Intent intent) {
            String condition = switch (value.summary()) {
                case "晴朗" -> "晴れ";
                case "多云" -> "曇り";
                case "有雾" -> "霧";
                case "有毛毛雨" -> "霧雨";
                case "有雨" -> "雨";
                case "有雪" -> "雪";
                case "有阵雨" -> "にわか雨";
                case "有阵雪" -> "にわか雪";
                case "有雷雨" -> "雷雨";
                default -> "天気不明";
            };
            String result = "%sの予報は%s、最低%.0f℃、最高%.0f℃、降水確率は最大%d%%です。".formatted(
                    value.location().name(), condition, value.temperatureMinC(),
                    value.temperatureMaxC(), value.rainProbabilityMax());
            return intent == Intent.OUTING ? result + " 道中、気をつけてね。" : result;
        }
    }
}
