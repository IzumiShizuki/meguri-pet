package com.meguri.core.react;

/** The only planner decisions accepted by Limited ReAct. */
public enum ReactDecision {
    CONTINUE,
    FINALIZE,
    WAIT_FOR_APPROVAL,
    DELEGATE_AGENT,
    FAIL
}
