package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderTest {
    private final TurnRequest request = new TurnRequest("u", "airi", "s", "我喜欢蓝色",
            List.of(), new ClientCapabilities(), null, null, true);
    private final RuntimeState state = new RuntimeState("airi", Mode.WORK, Relationship.SIBLING, "01",
            "2026-07-18T10:00:00+08:00", false, false, false, List.of(ExpressionTag.NEUTRAL, ExpressionTag.HAPPY));

    @Test
    void mockProviderIsDeterministicAndExtractsCandidate() {
        StepVerifier.create(new MockLlmProvider().respond(request, state, List.of(), List.of(), List.of()))
                .assertNext(response -> {
                    assertEquals("收到，我会按当前任务继续处理：我喜欢蓝色", response.getReply());
                    assertEquals(ExpressionTag.NEUTRAL, response.getExpressionTag());
                    assertEquals(1, response.getMemoryCandidates().size());
                    assertEquals(.8, response.getMemoryCandidates().getFirst().getConfidence());
                })
                .verifyComplete();
    }

    @Test
    void streamSplitsValidatedReplyIntoNonEmptyDeltas() {
        StepVerifier.create(new MockLlmProvider().stream(request, state, List.of(), List.of(), List.of()))
                .recordWith(java.util.ArrayList::new)
                .expectNextCount(2)
                .consumeRecordedWith(values -> assertTrue(values.stream().allMatch(value -> !value.isBlank())))
                .verifyComplete();
    }
}
