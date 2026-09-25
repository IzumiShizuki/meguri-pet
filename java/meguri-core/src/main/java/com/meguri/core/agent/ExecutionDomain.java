package com.meguri.core.agent;

public enum ExecutionDomain {
    INLINE,
    NON_BLOCKING_IO,
    BLOCKING_IO,
    CPU,
    PROVIDER,
    REMOTE_AGENT,
    BACKGROUND
}
