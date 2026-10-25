package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReachOutProviderTest {
    @Test
    void preservesPersonaAndKeepsBoundedHistoryInTheDataMessage() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        var provider = provider("兄长，要接着聊聊吗？", captured);
        assertThat(provider.generateReachOut(List.of("user: history-data", "assistant: recent answer")).block())
                .isEqualTo("兄长，要接着聊聊吗？");
        assertThat(captured.get().messages().getFirst().toString()).contains("MEGURI_PERSONA", "没有工具").doesNotContain("history-data");
        assertThat(captured.get().messages().getLast().toString()).contains("recent_conversation_data", "history-data");
        assertThat(captured.get().toolSpecifications()).isNullOrEmpty();
        assertThat(captured.get().parameters().maxOutputTokens()).isEqualTo(240);
    }

    @Test
    void declinesEmptySkipMalformedAndOversizedReplies() {
        for (String text : List.of("", "[SKIP]", "x".repeat(241), "{\"reply\":\"wrong format\"}", "```json\nwrong format")) {
            assertThat(provider(text, new AtomicReference<>()).generateReachOut(List.of("user: goodbye")).block())
                    .as("invalid or declined output: %s", text.substring(0, Math.min(20, text.length()))).isNull();
        }
    }

    private LangChain4jLlmProvider provider(String text, AtomicReference<ChatRequest> captured) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
        return new LangChain4jLlmProvider(model, new ObjectMapper(), "MEGURI_PERSONA");
    }
}
