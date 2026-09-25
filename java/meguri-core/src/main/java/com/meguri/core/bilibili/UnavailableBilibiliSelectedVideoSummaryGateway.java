package com.meguri.core.bilibili;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Metadata-only phase guard: no page, subtitle, or media is fetched. */
@Service
public final class UnavailableBilibiliSelectedVideoSummaryGateway
        implements BilibiliSelectedVideoSummaryGateway {
    @Override
    public Mono<BilibiliSelectedVideoSummaryResult> summarize(BilibiliSelectedVideoSummaryRequest request) {
        return Mono.just(BilibiliSelectedVideoSummaryResult.unavailable(request.selectedBvids()));
    }
}
