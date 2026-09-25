package com.meguri.core.bilibili;

import reactor.core.publisher.Mono;

/** Future extension point; implementations must never expand beyond the explicit BV selection. */
public interface BilibiliSelectedVideoSummaryGateway {
    Mono<BilibiliSelectedVideoSummaryResult> summarize(BilibiliSelectedVideoSummaryRequest request);
}
