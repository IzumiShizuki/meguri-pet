package com.meguri.core.execution;

@FunctionalInterface
public interface ExecutionModeResolver {
    ExecutionModeDecision resolve(
            CurrentTurnSignals signals,
            ExecutionModeResolutionContext context);
}
