package com.meguri.core.agent;

import java.util.List;
import java.util.Optional;

public final class AgentExecutionStores {
    private AgentExecutionStores() {
    }

    public interface SkillExecutionStore {
        SkillExecution create(SkillExecution execution);
        Optional<SkillExecution> findSkill(String executionId);
        SkillExecution save(SkillExecution execution, long expectedVersion);
    }

    public interface StepExecutionStore {
        StepExecution create(StepExecution execution);
        Optional<StepExecution> findStep(String stepExecutionId);
        List<StepExecution> findSteps(String executionId);
        StepExecution save(StepExecution execution, long expectedVersion);
    }

    public interface AgentTaskStore {
        CreateResult createIdempotent(AgentTask task);
        Optional<AgentTask> findTask(String taskId);
        Optional<AgentTask> findIdempotent(String tenantId, String userId, String idempotencyKey);
        Optional<AgentTask> findByRemoteTaskId(String remoteTaskId);
        List<AgentTask> findChildren(String parentTaskId);
        List<AgentTask> findResumable();
        AgentTask save(AgentTask task, long expectedVersion);
    }

    public interface RuntimeStore extends
            SkillExecutionStore, StepExecutionStore, AgentTaskStore {
    }

    public record CreateResult(AgentTask task, boolean created) {
    }
}
