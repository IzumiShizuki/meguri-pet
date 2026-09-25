package com.meguri.core.llm;

import dev.langchain4j.data.message.ImageContent;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultimodalAttachmentResolverTest {
    @Test
    void resolvesAUserApprovedInlineImage() {
        MultimodalAttachmentResolver resolver = new MultimodalAttachmentResolver(32, 64);

        var contents = resolver.resolve(List.of(Map.of(
                "type", "inline_attachment",
                "source", "airi_inline",
                "mime_type", "image/png",
                "data_url", "data:image/png;base64,aGVsbG8=",
                "content_access", "multimodal_read")));

        assertEquals(1, contents.size());
        assertInstanceOf(ImageContent.class, contents.getFirst());
    }

    @Test
    void refusesUnsupportedOrOversizedInlineContent() {
        MultimodalAttachmentResolver resolver = new MultimodalAttachmentResolver(4, 8);

        assertThrows(LlmProviderException.class, () -> resolver.resolve(List.of(Map.of(
                "type", "inline_attachment", "source", "airi_inline",
                "mime_type", "image/svg+xml", "data_url", "data:image/svg+xml;base64,AA==",
                "content_access", "multimodal_read"))));
        assertThrows(LlmProviderException.class, () -> resolver.resolve(List.of(Map.of(
                "type", "inline_attachment", "source", "airi_inline",
                "mime_type", "image/png", "data_url", "data:image/png;base64,aGVsbG8=",
                "content_access", "multimodal_read"))));
    }

    @Test
    void mockProviderRefusesToPretendItReadAMultimodalAttachment() {
        TurnRequest request = new TurnRequest(
                "user", "airi", "session", "look at this",
                List.of(Map.of("content_access", "multimodal_read")),
                new ClientCapabilities(), null, null, false);
        RuntimeState state = new RuntimeState("airi", Mode.WORK, Relationship.SIBLING,
                "01", "2026-08-01T00:00:00+08:00", false, false, false,
                List.of(ExpressionTag.NEUTRAL));

        assertThrows(LlmProviderException.class,
                () -> new MockLlmProvider().respond(request, state, List.of(), List.of(), List.of()).block());
    }
}
