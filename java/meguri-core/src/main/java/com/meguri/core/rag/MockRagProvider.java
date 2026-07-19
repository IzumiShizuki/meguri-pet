package com.meguri.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

/** Compatibility name retained for offline tests and local development. */
public final class MockRagProvider extends CanonicalRagRetriever {
    public MockRagProvider(Path dataRoot) { super(dataRoot); }
    public MockRagProvider(Path dataRoot, ObjectMapper mapper, String expectedBuildId) {
        super(dataRoot, mapper, expectedBuildId);
    }
}
