package com.meguri.core.conversation;

import com.meguri.core.llm.ConversationBoundary;
import com.meguri.core.llm.LangChain4jLlmProvider;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.runtime.TurnOrchestrator;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Makes a fail-closed semantic choice after the desktop's provisional three-turn window. */
@Service
public final class ConversationBoundaryService {
    private final TurnOrchestrator orchestrator;
    private final LlmProvider llm;

    public ConversationBoundaryService(TurnOrchestrator orchestrator, LlmProvider llm) {
        this.orchestrator = orchestrator;
        this.llm = llm;
    }

    public Mono<ConversationBoundaryDecision> decide(ConversationBoundaryRequest request) {
        validate(request);
        List<String> previous = lines(request.userId(), request.clientId(), request.parentSessionId());
        List<String> candidate = lines(request.userId(), request.clientId(), request.candidateSessionId());
        if (candidate.stream().filter(line -> line.startsWith("user: ")).count() < 2) {
            return Mono.just(merge(request, 0d, "candidate_window_too_short"));
        }
        if (!(llm instanceof LangChain4jLlmProvider provider)) {
            return Mono.just(merge(request, 0d, "classifier_unavailable"));
        }
        double minimum = Math.max(0.5d, Math.min(0.99d, request.minimumConfidence()));
        return provider.classifyBoundary(previous, candidate)
                .map(boundary -> apply(request, boundary, minimum))
                .onErrorReturn(merge(request, 0d, "classifier_unavailable"));
    }

    private ConversationBoundaryDecision apply(ConversationBoundaryRequest request, ConversationBoundary boundary, double minimum) {
        if (!boundary.sameContext() && boundary.confidence() >= minimum) {
            return new ConversationBoundaryDecision(true, boundary.confidence(), "new_topic", request.candidateSessionId());
        }
        return merge(request, boundary.confidence(), boundary.sameContext() ? "same_context" : "confidence_below_threshold");
    }

    private ConversationBoundaryDecision merge(ConversationBoundaryRequest request, double confidence, String reason) {
        orchestrator.mergeCandidateSession(request.userId(), request.clientId(), request.parentSessionId(), request.candidateSessionId());
        return new ConversationBoundaryDecision(false, confidence, reason, request.parentSessionId());
    }

    private List<String> lines(String userId, String clientId, String sessionId) {
        return orchestrator.sessionMessages(userId, clientId, sessionId).stream()
                .map(item -> item.role() + ": " + item.content()).toList();
    }

    private static void validate(ConversationBoundaryRequest request) {
        if (request == null || blank(request.userId()) || blank(request.clientId())
                || blank(request.parentSessionId()) || blank(request.candidateSessionId())) {
            throw new IllegalArgumentException("conversation boundary identity must not be blank");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
