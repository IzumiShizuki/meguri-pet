package com.meguri.core.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class McpExecutionMetadataCompatibilityTest {
    private final McpCapabilityNormalizer normalizer = new McpCapabilityNormalizer();

    @Test void capturedAnonymousExaToolsRetainSchemasAndExternalTrust() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mcp/exa-tools-public-20261030.json")) {
            assertNotNull(stream);
            var mapper = new ObjectMapper();
            var tools = mapper.readTree(stream).path("result").path("tools");
            assertEquals(2, tools.size());
            for (var tool : tools) {
                var raw = mapper.convertValue(tool, new TypeReference<Map<String,Object>>() {});
                var descriptor = normalizer.normalize("exa-audit", raw, 4);
                assertEquals(CapabilityDescriptor.Kind.READ_TOOL, descriptor.kind());
                assertEquals(CapabilityDescriptor.ApprovalRequirement.RISK_BASED, descriptor.approval());
                assertEquals(CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL, descriptor.resultTrust());
                assertTrue(descriptor.network().allowed());
                if (descriptor.id().endsWith("web_search_exa")) {
                    assertTrue(descriptor.inputSchema().required().containsAll(java.util.Set.of("query", "objective")));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"forbidden","optional"})
    void normalCallsAreAllowedWithoutLoweringUnknownToolRisk(String mode) {
        var tool=tool();
        tool.put("execution",Map.of("taskSupport",mode));
        var descriptor=normalizer.normalize("fixture",tool,4);
        assertEquals(CapabilityDescriptor.Kind.WRITE_TOOL,descriptor.kind());
        assertEquals(CapabilityDescriptor.ApprovalRequirement.ALWAYS,descriptor.approval());
    }

    @Test void missingTaskSupportDefaultsToNormalCall() {
        var tool=tool(); tool.put("execution",Map.of());
        assertNotNull(normalizer.normalize("fixture",tool,4));
    }

    @ParameterizedTest @ValueSource(strings={"required","unknown","FORBIDDEN"})
    void taskOnlyAndUnknownExecutionModesFailClosed(String mode) {
        var tool=tool();tool.put("execution",Map.of("taskSupport",mode));
        assertThrows(IllegalArgumentException.class,()->normalizer.normalize("fixture",tool,4));
    }

    @Test void malformedExecutionAndUnknownFieldsFailClosed() {
        for(Object execution : new Object[]{"forbidden",Map.of("taskSupport",true),
                Map.of("taskSupport","forbidden","command","anything"),null}) {
            var tool=tool();tool.put("execution",execution);
            assertThrows(IllegalArgumentException.class,()->normalizer.normalize("fixture",tool,4));
        }
    }

    @Test void newMetadataDoesNotBypassSecretOrUnknownMetadataRejection() {
        var tool=tool();tool.put("execution",Map.of("taskSupport","forbidden","api_key","synthetic"));
        assertThrows(IllegalArgumentException.class,()->normalizer.normalize("fixture",tool,4));
        var unknown=tool();unknown.put("execution",Map.of("taskSupport","forbidden"));unknown.put("command","anything");
        assertThrows(IllegalArgumentException.class,()->normalizer.normalize("fixture",unknown,4));
    }

    @Test void legacyToolMetadataRemainsAccepted() {
        assertNotNull(normalizer.normalize("fixture",tool(),3));
    }

    @Test void absentOutputSchemaAllowsResultDataButExplicitSchemaAndMalformedSchemaRemainStrict() {
        var raw = tool();
        var absent = normalizer.normalize("fixture", raw, 4);
        assertDoesNotThrow(() -> absent.outputSchema().validate(Map.of("content", java.util.List.of(Map.of("type", "text", "text", "public"))), "result"));
        assertEquals(CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL, absent.resultTrust());
        raw.put("outputSchema", Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")), "required", java.util.List.of("value")));
        var explicit = normalizer.normalize("fixture", raw, 4);
        assertThrows(CapabilityDescriptor.SchemaViolation.class, () -> explicit.outputSchema().validate(Map.of("value", 42), "result"));
        raw.put("outputSchema", null);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("fixture", raw, 4));
    }

    private Map<String,Object> tool() {
        return new LinkedHashMap<>(Map.of("name","lookup",
                "inputSchema",Map.of("type","object","properties",Map.of())));
    }
}
