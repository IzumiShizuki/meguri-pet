package com.meguri.core.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meguri.core.harness.retrieval.RetrievalMode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DtoContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void inboundRequestUsesSnakeCaseAndIgnoresAdapterExtras() throws Exception {
        TurnRequest request = mapper.readValue("""
                {"user_id":"u1","client_id":"airi","session_id":"s1","message":"hello",
                 "client_capabilities":{"text":true,"voice":true},"unknown_adapter_field":42}
                """, TurnRequest.class);
        assertEquals("u1", request.getUserId());
        assertEquals(true, request.getClientCapabilities().isVoice());
        assertEquals(false, request.isFormalMemoryAllowed());
        assertEquals(RetrievalMode.SLOW, request.retrievalMode());
        assertEquals("airi", mapper.readTree(mapper.writeValueAsString(request)).get("client_id").asText());
        assertEquals("SLOW", mapper.readTree(mapper.writeValueAsString(request)).get("retrieval_mode").asText());
    }

    @Test
    void retrievalModeIsTypedCaseInsensitiveAndRejectsUnknownValues() throws Exception {
        TurnRequest request = mapper.readValue("""
                {"user_id":"u1","client_id":"website","session_id":"s1","message":"hello",
                 "retrieval_mode":"fast"}
                """, TurnRequest.class);

        assertEquals(RetrievalMode.FAST, request.retrievalMode());
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"user_id":"u1","client_id":"website","session_id":"s1","message":"hello",
                 "retrieval_mode":"turbo"}
                """, TurnRequest.class));
    }

    @Test
    void memoryPermissionCanOnlyBeReboundByAnAuthenticatedBoundary() throws Exception {
        TurnRequest request = mapper.readValue(
                "{\"user_id\":\"u1\",\"client_id\":\"airi\",\"session_id\":\"s1\",\"message\":\"hello\",\"formal_memory_allowed\":true}",
                TurnRequest.class);
        assertEquals(true, request.isFormalMemoryAllowed());
        assertEquals(false, request.withFormalMemoryAllowed(false).isFormalMemoryAllowed());
    }

    @Test
    void responseRejectsUnknownPropertiesAndInvalidCandidates() throws Exception {
        String valid = """
                {"reply":"ok","expression_tag":"happy","expression_intensity":"medium",
                 "voice_style":"soft","memory_candidates":[]}
                """;
        assertEquals("ok", mapper.readValue(valid, LlmResponse.class).getReply());
        assertThrows(Exception.class, () -> mapper.readValue(valid.replace("}", ",\"extra\":true}"), LlmResponse.class));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmResponse("ok", ExpressionTag.NEUTRAL, Intensity.LOW, VoiceStyle.NEUTRAL,
                        List.of(new MemoryCandidate(MemoryType.PREFERENCE, "x", .5),
                                new MemoryCandidate(MemoryType.PREFERENCE, "x", .5),
                                new MemoryCandidate(MemoryType.PREFERENCE, "x", .5),
                                new MemoryCandidate(MemoryType.PREFERENCE, "x", .5))));
    }

    @Test
    void turnRequestRejectsUnknownClient() {
        assertThrows(IllegalArgumentException.class, () -> new TurnRequest("u", "unknown", "s", "m"));
    }
}
