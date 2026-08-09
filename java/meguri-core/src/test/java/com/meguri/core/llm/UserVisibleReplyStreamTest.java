package com.meguri.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class UserVisibleReplyStreamTest {
    @Test
    void passesNaturalLanguageThroughWithoutChangingChunkBoundaries() {
        List<String> result = UserVisibleReplyStream.sanitize(
                        Flux.just("收", "到。"))
                .collectList()
                .block();

        assertThat(result).containsExactly("收", "到。");
    }

    @Test
    void unwrapsReplyFromChunkedProviderJson() {
        List<String> result = UserVisibleReplyStream.sanitize(Flux.just(
                        "{\n  \"rep",
                        "ly\": \"第一行\\n第",
                        "二行\\\"引用\\\"\",\n  \"expression_tag\": \"neutral\"}"))
                .collectList()
                .block();

        assertThat(result).doesNotContain("{\n  \"rep");
        assertThat(String.join("", result)).isEqualTo("第一行\n第二行\"引用\"");
    }

    @Test
    void decodesUnicodeEscapesSplitAcrossChunks() {
        String result = UserVisibleReplyStream.sanitize(Flux.just(
                        "{\"reply\":\"emoji \\uD8",
                        "3D\\uDE00\",\"memory_candidates\":[]}"))
                .collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(result).isEqualTo("emoji 😀");
    }

    @Test
    void treatsBracePrefixedNaturalLanguageAsPlainTextOnFirstMismatch() {
        List<String> result = UserVisibleReplyStream.sanitize(
                        Flux.just("{示例", "内容}"))
                .collectList()
                .block();

        assertThat(result).containsExactly("{示例", "内容}");
    }
}
