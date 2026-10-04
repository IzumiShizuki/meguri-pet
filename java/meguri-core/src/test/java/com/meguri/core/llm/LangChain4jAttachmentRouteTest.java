package com.meguri.core.llm;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jAttachmentRouteTest {
    @Test
    void extractedDocumentsStayOnTheTextModel() {
        assertThat(LangChain4jLlmProvider.requiresVisualModel(
                List.of(TextContent.from("approved document text")))).isFalse();
    }

    @Test
    void imagesAndPdfsRequireAnExplicitVisionRoute() {
        assertThat(LangChain4jLlmProvider.requiresVisualModel(
                List.of(ImageContent.from("aGVsbG8=", "image/png")))).isTrue();
        assertThat(LangChain4jLlmProvider.requiresVisualModel(
                List.of(PdfFileContent.from("JVBERi0=", "application/pdf")))).isTrue();
    }
}
