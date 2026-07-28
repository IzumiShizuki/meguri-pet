package com.meguri.core.adapter.domain;

/** Controls how an at-least-once event is applied during client recovery. */
public enum ReplayPolicy {
    STATE,
    ONCE,
    ALWAYS
}
