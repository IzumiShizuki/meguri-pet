package com.meguri.core.capability;

import java.util.Map;

@FunctionalInterface
public interface CapabilityImplementation {
    Map<String, Object> invoke(Map<String, Object> input, ExecutionContext context) throws Exception;

    record ExecutionContext(String turnId, String traceId, String operationId, int attempt) { }
}
