package com.meguri.core.persona.runtime;

public record ClientCapabilityState(String clientType, boolean voice, boolean expression,
                                    boolean gesture, boolean screenContext, boolean streaming) { }
