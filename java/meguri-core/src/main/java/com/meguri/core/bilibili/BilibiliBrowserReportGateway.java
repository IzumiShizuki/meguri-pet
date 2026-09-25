package com.meguri.core.bilibili;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** Asynchronous boundary for generating the local browser page-visit report. */
@FunctionalInterface
public interface BilibiliBrowserReportGateway {
    Mono<BilibiliBrowserBriefing> generate(LocalDate targetDate);
}
