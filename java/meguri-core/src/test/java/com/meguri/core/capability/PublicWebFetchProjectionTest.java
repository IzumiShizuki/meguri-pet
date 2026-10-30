package com.meguri.core.capability;

import com.meguri.core.agent.CancellationToken;
import com.meguri.core.agent.StepDispatcher;
import com.meguri.core.react.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real fetch capability and frozen facade, with only the HTTP response synthesized. */
class PublicWebFetchProjectionTest {
    private static final String MARKER = "UNIQUE_DOCUMENT_BODY_7C91";

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void longSourceNeverStarvesBodyOrProducesAPartialUrl(boolean reversed) {
        String source = url("source", 4090);
        var observation = observe(source, "<p>" + MARKER + "</p><a href='https://docs.example/short'>short</a>", reversed);
        assertThat(observation.summary()).contains(MARKER, "Document text:", "[URL omitted");
        assertThat(observation.summary()).doesNotContain(source.substring(0, 100));
        assertOnlyWholeUrls(observation.summary(), Set.of(source, "https://docs.example/short"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void two2031CharacterLinksNeverDisplaceTheOnlyBodyText(boolean reversed) {
        String first = url("first", 2031), second = url("second", 2031);
        // Marker after links also covers the extractor's duplicate parenthesized hrefs in text.
        var observation = observe("https://docs.example/start",
                "<a href='" + first + "'>first</a><a href='" + second + "'>second</a><p>" + MARKER + "</p>", reversed);
        assertThat(observation.summary()).contains(MARKER, "Document text:", "[URL omitted");
        assertOnlyWholeUrls(observation.summary(), Set.of("https://docs.example/start", first, second));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void manyOrdinaryLinksPreserveTailBodyAndExplicitlyReportOmissions(boolean reversed) {
        StringBuilder html = new StringBuilder();
        Set<String> urls = new HashSet<>(Set.of("https://docs.example/start"));
        for (int i = 0; i < 32; i++) {
            String target = url("ordinary" + i, 120);
            urls.add(target);
            html.append("<a href='").append(target).append("'>result ").append(i).append("</a>");
        }
        html.append("<p>").append(MARKER).append("</p>");
        var observation = observe("https://docs.example/start", html.toString(), reversed);
        assertThat(observation.summary()).contains(MARKER, "Document text:", "[Additional links omitted]");
        assertOnlyWholeUrls(observation.summary(), urls);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void boundedBodyNeverCutsThroughAnUnlistedUrlOrEmoji(boolean reversed) {
        String bodyUrl = url("body", 2031);
        var observation = observe("https://docs.example/start",
                "<p>" + MARKER + " " + "\uD83D\uDE00".repeat(40) + " " + bodyUrl + " tail</p>", reversed);
        assertThat(observation.summary()).contains(MARKER, "[URL omitted", "[truncated]");
        assertOnlyWholeUrls(observation.summary(), Set.of("https://docs.example/start", bodyUrl));
        String summary = observation.summary();
        for (int i = 0; i < summary.length(); i++) {
            if (Character.isHighSurrogate(summary.charAt(i)))
                assertThat(i + 1 < summary.length() && Character.isLowSurrogate(summary.charAt(++i))).isTrue();
            else assertThat(Character.isLowSurrogate(summary.charAt(i))).isFalse();
        }
    }

    private static RawReactObservation observe(String source, String html, boolean reversed) {
        try (var runtime = new CapabilityRuntimeFacade(8); var dispatcher = new StepDispatcher(1,1,1,1)) {
            var capability = new PublicWebFetchCapability(PublicWebFetcherTest.fetcher(
                    target -> Mono.just(PublicWebFetcherTest.page(html))));
            runtime.register(PublicWebFetchCapability.descriptor(), (input, execution) -> {
                Map<String,Object> result = capability.invoke(input, execution);
                List<String> keys = new ArrayList<>(List.of("url", "title", "text", "links", "truncated", "linksTruncated", "redirects", "trust"));
                if (reversed) Collections.reverse(keys);
                Map<String,Object> ordered = new LinkedHashMap<>();
                for (String key : keys) {
                    Object value = result.get(key);
                    if (key.equals("links")) {
                        List<Map<String,Object>> links = new ArrayList<>();
                        for (Object item : (List<?>)value) {
                            Map<?,?> original = (Map<?,?>)item;
                            Map<String,Object> link = new LinkedHashMap<>();
                            for (String field : reversed ? List.of("url","text") : List.of("text","url"))
                                link.put(field, original.get(field));
                            links.add(link);
                        }
                        value = links;
                    }
                    ordered.put(key, value);
                }
                return ordered;
            });
            var frozen = runtime.freeze(new ExposurePlanner.ExposureContext("projection", "tenant", "synthetic-user", "test",
                    Set.of("capability:read"), CapabilityDescriptor.Mode.DEEP, Set.of("web_fetch"), 1, true,
                    CapabilityDescriptor.DataClassification.PUBLIC));
            var action = new ReactAction("web_fetch", Map.of("url", source));
            var context = new ReactExecutionContext(new ReactInvocationScope("projection", "trace", "tenant", "synthetic-user", "test",
                    Set.of("capability:read"), frozen.snapshotId(), true), 1, action.digest(), Instant.now().plusSeconds(5),
                    5000, 0, new CancellationToken());
            var observed = new CapabilityRuntimeReactActionExecutor(runtime, frozen, dispatcher, Clock.systemUTC())
                    .execute(new ValidatedReactAction(action, PublicWebFetchCapability.descriptor(), action.digest()), context).block();
            assertThat(observed.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(observed.summary()).hasSizeLessThanOrEqualTo(4096);
            assertThat(observed.data()).containsOnlyKeys("result_digest");
            assertThat(observed.trustLabel()).isEqualTo(ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT);
            assertThat(observed.tokensUsed()).isPositive();
            assertThat(new DefaultObservationNormalizer(4096).normalize(observed).summary()).contains(MARKER);
            return observed;
        }
    }

    private static String url(String tag, int length) {
        String prefix = "https://docs.example/" + tag + "?q=";
        return prefix + "x".repeat(length - prefix.length());
    }
    private static void assertOnlyWholeUrls(String summary, Set<String> expected) {
        var matches = java.util.regex.Pattern.compile("https?://[^\\s)]+").matcher(summary);
        while (matches.find()) assertThat(expected).as("URL must be complete: %s", matches.group()).contains(matches.group());
    }
}
