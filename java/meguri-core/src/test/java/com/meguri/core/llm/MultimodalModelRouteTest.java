package com.meguri.core.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultimodalModelRouteTest {
    private final ChatModel primary = new ChatModel() { };
    private final ChatModel fallback = new ChatModel() { };

    @Test
    void keepsADeclaredPrimaryModelForMultimodalContent() {
        MultimodalModelRoute route = new MultimodalModelRoute(
                Set.of("deepseek-v4-pro"), fallback, null, "dsv4p");

        assertSame(primary, route.select("deepseek-v4-pro", primary, null, true).model());
    }

    @Test
    void usesDsv4pFallbackWhenPrimaryIsNotDeclaredCapable() {
        MultimodalModelRoute route = new MultimodalModelRoute(
                Set.of("deepseek-v4-pro"), fallback, null, "dsv4p");

        assertSame(fallback, route.select("deepseek-v4-flash", primary, null, true).model());
    }

    @Test
    void failsBeforeCallingAnyModelWhenNoFallbackIsConfigured() {
        MultimodalModelRoute route = MultimodalModelRoute.disabled();

        assertThrows(LlmProviderException.class,
                () -> route.select("deepseek-v4-flash", primary, null, true));
    }
}
