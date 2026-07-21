package com.meguri.core.runtime;

import com.meguri.core.dto.Mode;
import com.meguri.core.dto.TurnRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStateMachineTest {
    @Test
    void switchesToSleepModeAutomaticallyAtNight() {
        Clock atElevenPmShanghai = Clock.fixed(Instant.parse("2026-07-21T15:00:00Z"), ZoneOffset.UTC);
        RuntimeStateMachine machine = new RuntimeStateMachine(atElevenPmShanghai);

        var state = machine.stateFor(new TurnRequest("u-test", "desktop_pet", "s-test", "晚安"));

        assertThat(state.getMode()).isEqualTo(Mode.SLEEP);
        assertThat(state.getOutfitCode()).isEqualTo("04");
    }
}
