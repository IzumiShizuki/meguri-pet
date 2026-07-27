package com.meguri.core.resources;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Loopback boundary for selecting local file metadata without opening files. */
@RestController
@RequestMapping("/v1/resources")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class ResourceSearchController {
    private static final int MAX_LIMIT = 20;
    private final ResourceSearchGateway gateway;

    @Autowired
    public ResourceSearchController(ObjectProvider<ResourceSearchGateway> gatewayProvider) {
        this(gatewayProvider.getIfAvailable(() -> (query, limit) -> Mono.just(
                ResourceSearchResponse.unavailable(query, "Everything 搜索服务尚未接入。"))));
    }

    ResourceSearchController(ResourceSearchGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResourceSearchResponse> search(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return Mono.just(ResourceSearchResponse.unavailable("", "请在 @ 后输入文件名关键词。"));
        }
        if (normalized.length() > 200) {
            return Mono.just(ResourceSearchResponse.unavailable(normalized.substring(0, 200), "资源关键词不能超过 200 个字符。"));
        }
        int boundedLimit = Math.max(1, Math.min(MAX_LIMIT, limit));
        return gateway.search(normalized, boundedLimit)
                .switchIfEmpty(Mono.just(ResourceSearchResponse.unavailable(normalized, "本地资源搜索没有返回结果。")))
                .onErrorReturn(ResourceSearchResponse.unavailable(normalized, "Everything 搜索服务暂时不可用。"));
    }
}
