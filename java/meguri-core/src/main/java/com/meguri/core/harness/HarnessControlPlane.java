package com.meguri.core.harness;

import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;

/** Authenticated health and operator controls, deliberately separate from turns. */
public interface HarnessControlPlane {
    HarnessDescription describe();

    RuntimeState resolvePersona(TurnRequest request);

    void setRuntimeOverride(String scope, RuntimeOverride override);

    void clearRuntimeOverride(String scope);

    record HarnessDescription(
            String protocolVersion,
            String buildId,
            String llmProvider,
            String ragProvider,
            String memoryProvider,
            String webSearchProvider,
            int ragChunks) { }
}
