package com.meguri.core.knowledge;

@FunctionalInterface
public interface NotionHttpPort {
    NotionHttpResponse execute(NotionHttpRequest request);
}
