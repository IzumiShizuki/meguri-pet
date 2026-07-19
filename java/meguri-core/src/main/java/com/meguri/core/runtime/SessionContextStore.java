package com.meguri.core.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded short-term context keyed by user, client and session. */
public final class SessionContextStore {
    public record Message(String role, String content) { }

    private final int capacity;
    private final Map<String, Deque<Message>> sessions = new ConcurrentHashMap<>();

    public SessionContextStore() {
        this(20);
    }

    public SessionContextStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public void append(String userId, String clientId, String sessionId, Message message) {
        String key = key(userId, clientId, sessionId);
        Deque<Message> queue = sessions.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(message);
            while (queue.size() > capacity) queue.removeFirst();
        }
    }

    public List<Message> recent(String userId, String clientId, String sessionId) {
        Deque<Message> queue = sessions.get(key(userId, clientId, sessionId));
        if (queue == null) return List.of();
        synchronized (queue) {
            return Collections.unmodifiableList(new ArrayList<>(queue));
        }
    }

    public void clear() {
        sessions.clear();
    }

    private static String key(String userId, String clientId, String sessionId) {
        return String.valueOf(userId) + "\u0000" + clientId + "\u0000" + sessionId;
    }
}
