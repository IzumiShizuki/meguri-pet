package com.meguri.core.agent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AgentRuntimeState {
    private AgentRuntimeState() {
    }

    public enum SkillStatus {
        PENDING, RUNNING, WAITING_REMOTE_AGENT, RESUMING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
    }

    public enum StepStatus {
        PENDING, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
    }

    public enum AgentStatus {
        CREATED, QUEUED, RUNNING, WAITING_EXTERNAL, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
    }

    private static final Map<SkillStatus, Set<SkillStatus>> SKILL_TRANSITIONS = Map.of(
            SkillStatus.PENDING, EnumSet.of(SkillStatus.RUNNING, SkillStatus.CANCELLED, SkillStatus.TIMED_OUT),
            SkillStatus.RUNNING, EnumSet.of(SkillStatus.WAITING_REMOTE_AGENT, SkillStatus.SUCCEEDED,
                    SkillStatus.FAILED, SkillStatus.CANCELLED, SkillStatus.TIMED_OUT),
            SkillStatus.WAITING_REMOTE_AGENT, EnumSet.of(SkillStatus.RESUMING, SkillStatus.FAILED,
                    SkillStatus.CANCELLED, SkillStatus.TIMED_OUT),
            SkillStatus.RESUMING, EnumSet.of(SkillStatus.SUCCEEDED, SkillStatus.FAILED,
                    SkillStatus.CANCELLED, SkillStatus.TIMED_OUT),
            SkillStatus.SUCCEEDED, Set.of(),
            SkillStatus.FAILED, Set.of(),
            SkillStatus.CANCELLED, Set.of(),
            SkillStatus.TIMED_OUT, Set.of());

    private static final Map<StepStatus, Set<StepStatus>> STEP_TRANSITIONS = Map.of(
            StepStatus.PENDING, EnumSet.of(StepStatus.RUNNING, StepStatus.CANCELLED, StepStatus.TIMED_OUT),
            StepStatus.RUNNING, EnumSet.of(StepStatus.WAITING, StepStatus.SUCCEEDED, StepStatus.FAILED,
                    StepStatus.CANCELLED, StepStatus.TIMED_OUT),
            StepStatus.WAITING, EnumSet.of(StepStatus.RUNNING, StepStatus.SUCCEEDED, StepStatus.FAILED,
                    StepStatus.CANCELLED, StepStatus.TIMED_OUT),
            StepStatus.SUCCEEDED, Set.of(),
            StepStatus.FAILED, Set.of(),
            StepStatus.CANCELLED, Set.of(),
            StepStatus.TIMED_OUT, Set.of());

    private static final Map<AgentStatus, Set<AgentStatus>> AGENT_TRANSITIONS = Map.of(
            AgentStatus.CREATED, EnumSet.of(AgentStatus.QUEUED, AgentStatus.CANCELLED, AgentStatus.TIMED_OUT),
            AgentStatus.QUEUED, EnumSet.of(AgentStatus.RUNNING, AgentStatus.FAILED,
                    AgentStatus.CANCELLED, AgentStatus.TIMED_OUT),
            AgentStatus.RUNNING, EnumSet.of(AgentStatus.WAITING_EXTERNAL, AgentStatus.SUCCEEDED,
                    AgentStatus.FAILED, AgentStatus.CANCELLED, AgentStatus.TIMED_OUT),
            AgentStatus.WAITING_EXTERNAL, EnumSet.of(AgentStatus.RUNNING, AgentStatus.SUCCEEDED,
                    AgentStatus.FAILED, AgentStatus.CANCELLED, AgentStatus.TIMED_OUT),
            AgentStatus.SUCCEEDED, Set.of(),
            AgentStatus.FAILED, Set.of(),
            AgentStatus.CANCELLED, Set.of(),
            AgentStatus.TIMED_OUT, Set.of());

    public static void requireTransition(SkillStatus from, SkillStatus to) {
        require(SKILL_TRANSITIONS, from, to, "skill");
    }

    public static void requireTransition(StepStatus from, StepStatus to) {
        require(STEP_TRANSITIONS, from, to, "step");
    }

    public static void requireTransition(AgentStatus from, AgentStatus to) {
        require(AGENT_TRANSITIONS, from, to, "agent task");
    }

    public static boolean terminal(SkillStatus status) {
        return status == SkillStatus.SUCCEEDED || status == SkillStatus.FAILED
                || status == SkillStatus.CANCELLED || status == SkillStatus.TIMED_OUT;
    }

    public static boolean terminal(StepStatus status) {
        return status == StepStatus.SUCCEEDED || status == StepStatus.FAILED
                || status == StepStatus.CANCELLED || status == StepStatus.TIMED_OUT;
    }

    public static boolean terminal(AgentStatus status) {
        return status == AgentStatus.SUCCEEDED || status == AgentStatus.FAILED
                || status == AgentStatus.CANCELLED || status == AgentStatus.TIMED_OUT;
    }

    private static <S extends Enum<S>> void require(Map<S, Set<S>> transitions, S from, S to, String type) {
        if (from == null || to == null || !transitions.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("illegal " + type + " transition: " + from + " -> " + to);
        }
    }
}
