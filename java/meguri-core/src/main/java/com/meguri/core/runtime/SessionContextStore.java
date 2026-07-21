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
    public record Snapshot(String userId, String clientId, String sessionId, List<Message> messages) { }
    private record Scope(String userId, String clientId, String sessionId) { }

    private final int capacity;
    private final Map<Scope, Deque<Message>> sessions = new ConcurrentHashMap<>();

    public SessionContextStore() {
        this(20);
    }

    public SessionContextStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public void append(String userId, String clientId, String sessionId, Message message) {
        Scope key = key(userId, clientId, sessionId);
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

    /** Immutable snapshots for the sleep-time summary job; no other session can leak into a snapshot. */
    public List<Snapshot> snapshots() {
        List<Snapshot> snapshots = new ArrayList<>();
        sessions.forEach((scope, queue) -> {
            synchronized (queue) {
                if (!queue.isEmpty()) {
                    snapshots.add(new Snapshot(scope.userId(), scope.clientId(), scope.sessionId(),
                            List.copyOf(new ArrayList<>(queue))));
                }
            }
        });
        return List.copyOf(snapshots);
    }

    public void clear() {
        sessions.clear();
    }

    private static Scope key(String userId, String clientId, String sessionId) {
        return new Scope(String.valueOf(userId), String.valueOf(clientId), String.valueOf(sessionId));
    }
}
