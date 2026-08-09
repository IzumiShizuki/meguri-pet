package com.meguri.core.llm;

import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.MemoryType;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.dto.VoiceStyle;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/** Deterministic offline provider used by local development and contract tests. */
public class MockLlmProvider implements LlmProvider {
    private static final Pattern MEMORY_HINT = Pattern.compile("我喜欢|我不喜欢|我的项目|我叫");

    @Override
    public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                     List<String> canon, List<String> memories,
                                     List<String> recentContext) {
        if (request.attachments().stream().anyMatch(MockLlmProvider::requiresMultimodalRead)) {
            return Mono.error(new LlmProviderException(
                    "Multimodal attachments require a configured openai-compatible model and "
                            + "MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL; the local Core is running in mock mode."));
        }
        String message = request.getMessage().trim();
        ExpressionTag tag;
        Intensity intensity;
        VoiceStyle voice;
        String reply;
        switch (state.getMode()) {
            case SLEEP -> {
                reply = "辛苦了。先慢慢休息一下吧：" + message;
                tag = ExpressionTag.SLEEPY;
                intensity = Intensity.LOW;
                voice = VoiceStyle.SLEEPY;
            }
            case WORK -> {
                reply = "收到，我会按当前任务继续处理：" + message;
                tag = ExpressionTag.NEUTRAL;
                intensity = Intensity.LOW;
                voice = VoiceStyle.RESTRAINED;
            }
            default -> {
                reply = "嗯，我听到了：" + message;
                tag = ExpressionTag.HAPPY;
                intensity = Intensity.MEDIUM;
                voice = VoiceStyle.SOFT;
            }
        }
        List<MemoryCandidate> candidates = MEMORY_HINT.matcher(message).find()
                ? List.of(new MemoryCandidate(MemoryType.PREFERENCE, message, 0.8)) : List.of();
        return Mono.just(new LlmResponse(reply, tag, intensity, voice, candidates));
    }

    @Override
    public String providerName() {
        return "mock";
    }

    private static boolean requiresMultimodalRead(Map<String, Object> attachment) {
        if (attachment == null) return false;
        Object access = attachment.get("content_access");
        return "multimodal_read".equals(access) || "document_read".equals(access);
    }
}
