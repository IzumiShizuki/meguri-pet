package com.meguri.core.resources;

import reactor.core.publisher.Mono;

/** Optional adapter for an Everything-backed, metadata-only file index. */
public interface ResourceSearchGateway {
    Mono<ResourceSearchResponse> search(String query, int limit);
}
