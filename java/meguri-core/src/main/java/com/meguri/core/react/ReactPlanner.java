package com.meguri.core.react;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ReactPlanner {
    Mono<ReactPlannerDecision> plan(ReactPlanningContext context);
}
