package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalPromptBudgetTest {
    @Test
    void usesProviderTokenizerAndPrecompressesOptionalLanesAtSeventyPercent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OpenAiProviderTokenizer tokenizer = new OpenAiProviderTokenizer("gpt-4o-mini");
        GlobalPromptBudget budget = new GlobalPromptBudget(mapper, tokenizer, 256);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runtime_state", Map.of("mode", "work"));
        context.put("user_message", "Please help with this task");
        context.put("canon_examples", List.of("canon ".repeat(40), "second ".repeat(40)));
        context.put("long_term_memories", List.of("memory ".repeat(40), "other ".repeat(40)));
        context.put("recent_context", List.of("recent ".repeat(40), "history ".repeat(40)));
        context.put("web_results", List.of("web ".repeat(40), "result ".repeat(40)));

        GlobalPromptBudget.BudgetedPrompt fitted = budget.fit("system", context);
        Map<?, ?> decoded = mapper.readValue(fitted.json(), Map.class);

        assertThat(fitted.precompressed()).isTrue();
        assertThat(fitted.tokensBefore()).isGreaterThan(fitted.tokensAfter());
        assertThat(fitted.tokensAfter()).isLessThanOrEqualTo((int) Math.floor(256 * 0.90d));
        assertThat(fitted.tokenizer()).isEqualTo("openai-compatible:gpt-4o-mini");
        assertThat(decoded.get("runtime_state")).isEqualTo(Map.of("mode", "work"));
        assertThat(decoded.get("user_message")).isEqualTo("Please help with this task");
    }

    @Test
    void failsWhenMandatoryContextCannotFitTheHardTarget() {
        GlobalPromptBudget budget = new GlobalPromptBudget(
                new ObjectMapper(), new OpenAiProviderTokenizer("gpt-4o-mini"), 256);

        assertThatThrownBy(() -> budget.fit("mandatory ".repeat(500), Map.of(
                        "runtime_state", Map.of("mode", "work"),
                        "user_message", "hello")))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("mandatory prompt context");
    }

    @Test
    void neverSilentlyTruncatesTheUserMessage() {
        GlobalPromptBudget budget = new GlobalPromptBudget(
                new ObjectMapper(), new OpenAiProviderTokenizer("gpt-4o-mini"), 256);

        assertThatThrownBy(() -> budget.fit("system", Map.of(
                        "runtime_state", Map.of("mode", "work"),
                        "user_message", "important user input ".repeat(500),
                        "web_results", List.of("optional result ".repeat(500)))))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("mandatory prompt context");
    }
}
