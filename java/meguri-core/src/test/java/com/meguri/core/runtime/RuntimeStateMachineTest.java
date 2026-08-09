package com.meguri.core.runtime;

import com.meguri.core.dto.Mode;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.TurnRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeStateMachineTest {
    @Test
    void switchesToSleepModeAutomaticallyAfterTheLateBedtime() {
        Clock atThreeAmShanghai = Clock.fixed(Instant.parse("2026-07-21T19:00:00Z"), ZoneOffset.UTC);
        RuntimeStateMachine machine = new RuntimeStateMachine(atThreeAmShanghai);

        var state = machine.stateFor(new TurnRequest("u-test", "desktop_pet", "s-test", "晚安"));

        assertThat(state.getMode()).isEqualTo(Mode.SLEEP);
        assertThat(state.getOutfitCode()).isEqualTo("04");
        assertThat(state.getRelationshipProfile()).isEqualTo(Relationship.SIBLING);
    }

    @Test
    void staysInPrivateModeAtHalfPastMidnightForALateSleepSchedule() {
        Clock atHalfPastMidnightShanghai =
                Clock.fixed(Instant.parse("2026-07-21T16:30:00Z"), ZoneOffset.UTC);
        RuntimeStateMachine machine = new RuntimeStateMachine(atHalfPastMidnightShanghai);

        var state = machine.stateFor(new TurnRequest("u-test", "desktop_pet", "s-test", "还不困"));

        assertThat(state.getMode()).isEqualTo(Mode.PRIVATE);
        assertThat(state.getOutfitCode()).isEqualTo("03");
    }

    @Test
    void temporalModeNeverChangesRelationship() {
        RuntimeStateMachine daytime = new RuntimeStateMachine(
                Clock.fixed(Instant.parse("2026-07-21T04:00:00Z"), ZoneOffset.UTC));
        RuntimeStateMachine nighttime = new RuntimeStateMachine(
                Clock.fixed(Instant.parse("2026-07-21T15:00:00Z"), ZoneOffset.UTC));
        TurnRequest request = new TurnRequest("u-test", "website", "s-test", "hello");

        assertThat(daytime.stateFor(request).getRelationshipProfile()).isEqualTo(Relationship.SIBLING);
        assertThat(nighttime.stateFor(request).getRelationshipProfile()).isEqualTo(Relationship.SIBLING);
    }

    @Test
    void runtimeOverrideCannotChangeSharedRelationship() {
        Instant instant = Instant.parse("2026-07-21T04:00:00Z");
        RuntimeStateMachine machine = new RuntimeStateMachine(Clock.fixed(instant, ZoneOffset.UTC));
        TurnRequest forgedRequest = new TurnRequest(
                "u-test", "website", "s-web", "hello", List.of(),
                new ClientCapabilities(), null, Relationship.LOVER, false);
        machine.setOverride("u-test:website", new RuntimeOverride(
                null, null, "05", OffsetDateTime.parse("2026-07-21T13:30:00+08:00")));

        var untrusted = machine.stateFor(forgedRequest);
        assertThat(untrusted.getRelationshipProfile()).isEqualTo(Relationship.SIBLING);
        assertThat(untrusted.getOutfitCode()).isEqualTo("05");

        assertThatThrownBy(() -> new RuntimeOverride(
                null, Relationship.PURSUIT, null, OffsetDateTime.parse("2026-07-21T13:30:00+08:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(machine.stateFor(forgedRequest).getRelationshipProfile()).isEqualTo(Relationship.SIBLING);
        assertThat(machine.stateFor(new TurnRequest("u-test", "airi", "s-airi", "hello"))
                .getRelationshipProfile()).isEqualTo(Relationship.SIBLING);
    }

    @Test
    void debouncesAndCoolsDownTemporalBoundaryChanges() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T17:59:00Z"));
        RuntimeStateMachine machine = new RuntimeStateMachine(
                clock, RuntimeStateMachine.DEFAULT_ZONE,
                Duration.ofSeconds(30), Duration.ofMinutes(2));
        TurnRequest request = new TurnRequest("u-test", "desktop_pet", "s-test", "hello");

        assertThat(machine.stateFor(request).getMode()).isEqualTo(Mode.PRIVATE);
        clock.set(Instant.parse("2026-07-21T18:00:05Z"));
        assertThat(machine.stateFor(request).getMode()).isEqualTo(Mode.PRIVATE);
        clock.set(Instant.parse("2026-07-21T18:00:20Z"));
        assertThat(machine.stateFor(request).getMode()).isEqualTo(Mode.PRIVATE);
        clock.set(Instant.parse("2026-07-21T18:01:01Z"));
        assertThat(machine.stateFor(request).getMode()).isEqualTo(Mode.SLEEP);
    }

    @Test
    void expiredUserOverrideFallsBackToActiveClientOverrideImmediately() {
        Instant instant = Instant.parse("2026-07-21T04:00:00Z");
        RuntimeStateMachine machine = new RuntimeStateMachine(Clock.fixed(instant, ZoneOffset.UTC));
        TurnRequest request = new TurnRequest("u-test", "desktop_pet", "s-test", "hello");
        machine.setOverride("u-test:desktop_pet", new RuntimeOverride(
                null, null, "05", OffsetDateTime.parse("2026-07-21T13:30:00+08:00")));
        machine.setOverride("u-test", new RuntimeOverride(
                null, null, "06", OffsetDateTime.parse("2026-07-21T11:30:00+08:00")));

        assertThat(machine.stateFor(request).getOutfitCode()).isEqualTo("05");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
