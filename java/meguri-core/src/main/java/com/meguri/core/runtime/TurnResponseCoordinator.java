package com.meguri.core.runtime;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.NativeTextDeltaAggregator;
import com.meguri.core.llm.ProviderRequest;
import com.meguri.core.llm.UserVisibleReplyStream;
import com.meguri.core.observability.TurnLatencyMissingReason;
import com.meguri.core.observability.TurnLatencyPoint;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService.TurnWeatherContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Owns provider response generation, visible streaming, and optional supervision. */
final class TurnResponseCoordinator {
    @FunctionalInterface
    interface EventEmitter {
        void emit(TurnRecord record, String type, Map<String, Object> data);
    }

    interface LatencyRecorder {
        void mark(TurnRecord record, TurnLatencyPoint point);

        void markMissing(TurnRecord record, TurnLatencyPoint point,
                         TurnLatencyMissingReason reason);
    }

    record GeneratedResponse(LlmResponse response, boolean nativeStream) { }

    private final LlmProvider llm;
    private final TrainingFeedbackService trainingFeedback;
    private final String buildId;
    private final EventEmitter events;
    private final LatencyRecorder latency;

    TurnResponseCoordinator(LlmProvider llm, TrainingFeedbackService trainingFeedback,
                            String buildId, EventEmitter events, LatencyRecorder latency) {
        this.llm = llm;
        this.trainingFeedback = trainingFeedback;
        this.buildId = buildId;
        this.events = events;
        this.latency = latency;
    }

    Mono<GeneratedResponse> generate(TurnRecord record, ProviderRequest request,
                                     TurnWeatherContext weather) {
        return generatePrimary(record, request)
                .map(generated -> new GeneratedResponse(
                        weather.augment(generated.response()), generated.nativeStream()))
                .flatMap(generated -> sampleAlternative(record, request, generated.response(), weather)
                        .map(response -> new GeneratedResponse(response, generated.nativeStream())));
    }

    private Mono<GeneratedResponse> generatePrimary(TurnRecord record, ProviderRequest request) {
        if (!llm.supportsNativeStreaming()) {
            latency.mark(record, TurnLatencyPoint.PROVIDER_REQUEST_SENT);
            return llm.respond(request)
                    .doOnNext(ignored -> {
                        latency.mark(record, TurnLatencyPoint.PROVIDER_FIRST_BYTE);
                        latency.markMissing(record, TurnLatencyPoint.PROVIDER_FIRST_TOKEN,
                                TurnLatencyMissingReason.PROVIDER_UNSUPPORTED);
                    })
                    .map(response -> new GeneratedResponse(response, false));
        }

        AtomicLong index = new AtomicLong();
        StringBuilder reply = new StringBuilder();
        latency.mark(record, TurnLatencyPoint.PROVIDER_REQUEST_SENT);
        Flux<String> providerStream = llm.stream(request)
                .doOnNext(delta -> {
                    if (delta != null && !delta.isEmpty()) {
                        latency.mark(record, TurnLatencyPoint.PROVIDER_FIRST_BYTE);
                        latency.mark(record, TurnLatencyPoint.PROVIDER_FIRST_TOKEN);
                    }
                });
        return NativeTextDeltaAggregator.aggregate(UserVisibleReplyStream.sanitize(providerStream))
                .doOnNext(delta -> {
                    if (record.isCancelRequested()) {
                        throw new java.util.concurrent.CancellationException(
                                "turn cancelled during provider stream");
                    }
                    events.emit(record, "text.delta", Map.of(
                            "delta", delta,
                            "index", index.incrementAndGet(),
                            "native", true));
                    reply.append(delta);
                })
                .then(Mono.defer(() -> {
                    if (reply.isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "LLM provider returned an empty stream"));
                    }
                    return llm.finalizeStream(reply.toString(), request)
                            .onErrorReturn(new LlmResponse(reply.toString()))
                            .map(response -> new GeneratedResponse(response, true));
                }));
    }

    private Mono<LlmResponse> sampleAlternative(TurnRecord record,
                                                 ProviderRequest providerRequest,
                                                 LlmResponse first,
                                                 TurnWeatherContext weather) {
        var request = providerRequest.turn();
        List<String> durableRecent = providerRequest.legacyRecentContext();
        if (!trainingFeedback.shouldCompare(request.trainingMode())) {
            if (request.trainingMode()) {
                registerTrainingObservation(record, request, durableRecent, first);
            }
            return Mono.just(first);
        }
        return llm.respondAlternative(providerRequest)
                .map(weather::augment)
                .doOnNext(second -> {
                    Optional<Map<String, Object>> event = trainingFeedback.registerComparison(
                            record.getTurnId(), request, durableRecent, first, second,
                            llm.providerName(), buildId);
                    event.ifPresent(value -> events.emit(record,
                            "training.candidates.ready", value));
                    if (event.isEmpty() && request.trainingMode()) {
                        registerTrainingObservation(record, request, durableRecent, first);
                    }
                })
                .thenReturn(first)
                .onErrorResume(error -> {
                    if (request.trainingMode()) {
                        registerTrainingObservation(record, request, durableRecent, first);
                    }
                    return Mono.just(first);
                });
    }

    private void registerTrainingObservation(TurnRecord record,
                                            com.meguri.core.dto.TurnRequest request,
                                            List<String> recent, LlmResponse response) {
        try {
            trainingFeedback.registerSingle(record.getTurnId(), request, recent, response,
                    llm.providerName(), buildId);
        } catch (IllegalStateException ignored) {
            // Optional supervision has its own storage health boundary; a generated reply remains deliverable.
        }
    }
}
