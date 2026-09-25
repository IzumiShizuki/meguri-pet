package com.meguri.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeStateTest {
    @Test
    void illegalAndPostTerminalTransitionsFailClosed() {
        Instant now = Instant.now();
        SkillExecution skill = new SkillExecution(
                "e", "t", "s", AgentRuntimeState.SkillStatus.PENDING,
                now.plusSeconds(1), null, 0, now, now);

        assertThatThrownBy(() -> skill.transition(AgentRuntimeState.SkillStatus.SUCCEEDED, null, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illegal skill transition");

        SkillExecution terminal = skill
                .transition(AgentRuntimeState.SkillStatus.RUNNING, null, now)
                .transition(AgentRuntimeState.SkillStatus.SUCCEEDED, null, now);
        assertThatThrownBy(() -> terminal.transition(AgentRuntimeState.SkillStatus.RUNNING, null, now))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> AgentRuntimeState.requireTransition(
                AgentRuntimeState.AgentStatus.CREATED, AgentRuntimeState.AgentStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
    }
}
