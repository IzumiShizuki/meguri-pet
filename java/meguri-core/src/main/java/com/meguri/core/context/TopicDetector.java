package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.List;

/** Detects topic evidence from the raw user input, never from a rewritten retrieval query. */
public interface TopicDetector {
    TopicSignal detect(String rawUserMessage, List<SessionContextStore.MessageNode> activePath);
}
