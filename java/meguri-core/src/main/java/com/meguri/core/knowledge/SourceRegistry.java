package com.meguri.core.knowledge;

import java.util.List;

public interface SourceRegistry {
    String sourceId();

    List<SourcePage> scan();
}
