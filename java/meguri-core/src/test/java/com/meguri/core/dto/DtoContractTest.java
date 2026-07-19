package com.meguri.core.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
        assertEquals(true, request.isFormalMemoryAllowed());
        assertEquals("airi", mapper.readTree(mapper.writeValueAsString(request)).get("client_id").asText());
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
