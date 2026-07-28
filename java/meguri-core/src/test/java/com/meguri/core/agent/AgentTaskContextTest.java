package com.meguri.core.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTaskContextTest {
    @Test
    void childCanOnlyNarrowBudgetDeadlineAndCapabilities() {
        Instant deadline = Instant.now().plusSeconds(60);
        CancellationToken parentCancel = new CancellationToken();
        AgentTaskContext parent = new AgentTaskContext(
                "tenant", "user", null, "trace", "root-span", "turn-key", deadline,
                parentCancel, new AgentTaskContext.Budget(1_000, 10, new BigDecimal("5.00"), 3, 4),
                0, Set.of("search", "read"), "parent brief", Map.of("document", "doc-1"));

        AgentTaskContext child = parent.child(new AgentTaskContext.ChildScope(
                "parent-task", "child-span", "research", deadline.minusSeconds(1),
                new AgentTaskContext.Budget(500, 4, new BigDecimal("2.00"), 2, 1),
                Set.of("read"), "only the delegated brief", Map.of("document", "doc-1")));

        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.deadline()).isBefore(parent.deadline());
        assertThat(child.allowedCapabilities()).containsExactly("read");
        assertThat(child.budget().maxTokens()).isEqualTo(500);
        assertThat(child.idempotencyKey()).isEqualTo("turn-key/research");
        assertThat(child.depth()).isEqualTo(1);
        assertThat(child.references()).containsOnlyKeys("document");

        assertThatThrownBy(() -> parent.child(new AgentTaskContext.ChildScope(
                "parent-task", "span", "bad-cap", deadline,
                parent.budget(), Set.of("admin"), "brief", Map.of())))
                .isInstanceOf(AgentPolicyException.class)
                .hasMessageContaining("capabilities");
        assertThatThrownBy(() -> parent.child(new AgentTaskContext.ChildScope(
                "parent-task", "span", "bad-budget", deadline,
                new AgentTaskContext.Budget(1_001, 10, new BigDecimal("5"), 3, 4),
                Set.of(), "brief", Map.of())))
                .isInstanceOf(AgentPolicyException.class)
                .hasMessageContaining("budget");
        assertThatThrownBy(() -> parent.child(new AgentTaskContext.ChildScope(
                "parent-task", "span", "bad-deadline", deadline.plusSeconds(1),
                parent.budget(), Set.of(), "brief", Map.of())))
                .isInstanceOf(AgentPolicyException.class)
                .hasMessageContaining("deadline");

        parentCancel.cancel();
        assertThat(child.cancellation().isCancelled()).isTrue();
    }

    @Test
    void depthLimitFailsClosed() {
        Instant deadline = Instant.now().plusSeconds(10);
        AgentTaskContext parent = new AgentTaskContext(
                "tenant", "user", null, "trace", "span", "key", deadline, new CancellationToken(),
                new AgentTaskContext.Budget(1, 1, BigDecimal.ONE, 0, 1),
                0, Set.of(), "brief", Map.of());

        assertThatThrownBy(() -> parent.child(new AgentTaskContext.ChildScope(
                null, "child", "child", deadline, parent.budget(), Set.of(), "child", Map.of())))
                .isInstanceOf(AgentPolicyException.class)
                .hasMessageContaining("depth");
    }
}
