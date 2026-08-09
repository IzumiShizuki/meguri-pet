package com.meguri.core.react;

import com.meguri.core.agent.CancellationToken;
import com.meguri.core.agent.StepDispatcher;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRuntimeReactActionExecutorTest {

    @Test
    void delegatesThroughFrozenFacadeAndRejectsSnapshotMismatch() {
        AtomicInteger calls = new AtomicInteger();
        try (CapabilityRuntimeFacade facade = new CapabilityRuntimeFacade(8);
             StepDispatcher dispatcher = new StepDispatcher(1, 1, 1, 1)) {
            facade.bindDefault(CapabilityRuntimeFacade.DEFAULT_READ_TOOL,
                    (input, context) -> {
                        calls.incrementAndGet();
                        return Map.of("value", "found");
                    });
            CapabilityRuntimeFacade.TurnCapabilities frozen = facade.freeze(
                    new ExposurePlanner.ExposureContext(
                            "turn", "tenant", "user", "website", Set.of(),
                            CapabilityDescriptor.Mode.DEEP,
                            Set.of(CapabilityRuntimeFacade.DEFAULT_READ_TOOL),
                            1, false,
                            CapabilityDescriptor.DataClassification.INTERNAL));
            CapabilityDescriptor descriptor = facade.exposedDescriptors(frozen).getFirst();
            ValidatedReactAction action = new ValidatedReactAction(
                    new ReactAction(descriptor.id(), Map.of("query", "target")),
                    descriptor,
                    new ReactAction(descriptor.id(), Map.of("query", "target")).digest());
            CapabilityRuntimeReactActionExecutor executor =
                    new CapabilityRuntimeReactActionExecutor(
                            facade, frozen, dispatcher, Clock.systemUTC());

            RawReactObservation accepted = executor.execute(
                    action, context(frozen.snapshotId())).block();
            RawReactObservation rejected = executor.execute(
                    action, context("forged-snapshot")).block();

            assertThat(accepted.status()).isEqualTo(RawReactObservation.Status.SUCCESS);
            assertThat(accepted.data()).containsEntry("value", "found");
            assertThat(accepted.trustLabel())
                    .isEqualTo(ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT);
            assertThat(rejected.status()).isEqualTo(RawReactObservation.Status.FAILED);
            assertThat(rejected.errorCode()).isEqualTo("CAPABILITY_SNAPSHOT_MISMATCH");
            assertThat(calls).hasValue(1);
        }
    }

    private static ReactExecutionContext context(String snapshot) {
        return new ReactExecutionContext(
                new ReactInvocationScope(
                        "turn", "trace", "tenant", "user", "website",
                        Set.of(), snapshot, false),
                1,
                "digest",
                Instant.now().plusSeconds(5),
                100,
                100,
                new CancellationToken());
    }
}
