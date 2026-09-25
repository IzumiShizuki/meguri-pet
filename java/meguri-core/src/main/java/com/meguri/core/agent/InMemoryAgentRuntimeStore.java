package com.meguri.core.agent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAgentRuntimeStore implements
        AgentExecutionStores.RuntimeStore {

    private final ConcurrentHashMap<String, SkillExecution> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StepExecution> steps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotency = new ConcurrentHashMap<>();

    @Override
    public SkillExecution create(SkillExecution execution) {
        if (skills.putIfAbsent(execution.executionId(), execution) != null) {
            throw new IllegalStateException("skill execution already exists");
        }
        return execution;
    }

    @Override
    public Optional<SkillExecution> findSkill(String executionId) {
        return Optional.ofNullable(skills.get(executionId));
    }

    @Override
    public SkillExecution save(SkillExecution execution, long expectedVersion) {
        return update(skills, execution.executionId(), expectedVersion, execution);
    }

    @Override
    public StepExecution create(StepExecution execution) {
        if (steps.putIfAbsent(execution.stepExecutionId(), execution) != null) {
            throw new IllegalStateException("step execution already exists");
        }
        return execution;
    }

    @Override
    public Optional<StepExecution> findStep(String stepExecutionId) {
        return Optional.ofNullable(steps.get(stepExecutionId));
    }

    @Override
    public List<StepExecution> findSteps(String executionId) {
        return steps.values().stream().filter(value -> value.executionId().equals(executionId)).toList();
    }

    @Override
    public StepExecution save(StepExecution execution, long expectedVersion) {
        return update(steps, execution.stepExecutionId(), expectedVersion, execution);
    }

    @Override
    public AgentExecutionStores.CreateResult createIdempotent(AgentTask task) {
        String scope = task.tenantId() + '\0' + task.userId() + '\0' + task.idempotencyKey();
        synchronized (idempotency) {
            String existingId = idempotency.get(scope);
            if (existingId != null) {
                AgentTask existing = tasks.get(existingId);
                if (!existing.payloadHash().equals(task.payloadHash())) {
                    throw new AgentPolicyException("idempotency key reused with a different proposal");
                }
                return new AgentExecutionStores.CreateResult(existing, false);
            }
            if (tasks.putIfAbsent(task.taskId(), task) != null) {
                throw new IllegalStateException("agent task already exists");
            }
            idempotency.put(scope, task.taskId());
            return new AgentExecutionStores.CreateResult(task, true);
        }
    }

    @Override
    public Optional<AgentTask> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public Optional<AgentTask> findIdempotent(String tenantId, String userId, String idempotencyKey) {
        String id = idempotency.get(tenantId + '\0' + userId + '\0' + idempotencyKey);
        return id == null ? Optional.empty() : Optional.ofNullable(tasks.get(id));
    }

    @Override
    public Optional<AgentTask> findByRemoteTaskId(String remoteTaskId) {
        return tasks.values().stream().filter(task -> remoteTaskId.equals(task.remoteTaskId())).findFirst();
    }

    @Override
    public List<AgentTask> findChildren(String parentTaskId) {
        return tasks.values().stream().filter(task -> java.util.Objects.equals(parentTaskId, task.parentTaskId())).toList();
    }

    @Override
    public List<AgentTask> findResumable() {
        return tasks.values().stream()
                .filter(task -> switch (task.status()) {
                    case CREATED, QUEUED, RUNNING, WAITING_EXTERNAL -> true;
                    case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT -> false;
                })
                .sorted(java.util.Comparator
                        .comparing(AgentTask::updatedAt)
                        .thenComparing(AgentTask::taskId))
                .toList();
    }

    @Override
    public AgentTask save(AgentTask task, long expectedVersion) {
        return update(tasks, task.taskId(), expectedVersion, task);
    }

    private static <T> T update(ConcurrentHashMap<String, T> values, String id, long expectedVersion, T next) {
        values.compute(id, (ignored, current) -> {
            if (current == null) throw new IllegalStateException("record does not exist: " + id);
            long actual = version(current);
            if (actual != expectedVersion) {
                throw new IllegalStateException("stale record version: expected " + expectedVersion + " but was " + actual);
            }
            return next;
        });
        return next;
    }

    private static long version(Object value) {
        if (value instanceof SkillExecution execution) return execution.version();
        if (value instanceof StepExecution execution) return execution.version();
        if (value instanceof AgentTask task) return task.version();
        throw new IllegalArgumentException("unsupported record type");
    }
}
