package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * Optional, rebuildable read-model seam. The Message DAG remains the authority;
 * a missing or unverifiable projection is always a normal full-Harness miss.
 */
public interface ConversationContextReadModelProvider {
    Optional<ConversationContextReadModel> find(
            ContextBuildRequest request, SessionContextStore.GraphSnapshot authority);

    default void rebuild(ContextBuildRequest request, SessionContextStore.GraphSnapshot authority) {
        // Implementations may asynchronously rebuild; correctness does not depend on it.
    }

    static ConversationContextReadModelProvider missing() {
        return (request, authority) -> Optional.empty();
    }

    static String digest(SessionContextStore.GraphSnapshot graph) {
        StringBuilder value = new StringBuilder("meguri-context-read-model-v1");
        graph.activePath().forEach(node -> value.append('|')
                .append(node.messageId()).append(':').append(node.role()).append(':').append(node.content()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    static boolean verified(ConversationContextReadModel model,
                            SessionContextStore.GraphSnapshot authority) {
        return verified(model, authority, null);
    }

    static boolean verified(ConversationContextReadModel model,
                            SessionContextStore.GraphSnapshot authority,
                            String expectedStrategyRevision) {
        if (model == null || model.graphRevision() != authority.revision()
                || !Objects.equals(model.conversationId(), authority.sessionId())
                || !java.util.Objects.equals(model.activeLeafMessageId(), authority.activeLeafMessageId())
                || !java.util.Objects.equals(model.sourceDigest(), digest(authority))
                || expectedStrategyRevision != null && !expectedStrategyRevision.isBlank()
                && !java.util.Objects.equals(model.strategyRevision(), expectedStrategyRevision)
                || !samePath(model.activePath(), authority.activePath())
                || model.tokenCount() < 0) return false;
        java.util.Set<String> authorityIds = authority.allNodes().stream()
                .map(SessionContextStore.MessageNode::messageId).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> activeIds = model.activePath().stream()
                .map(SessionContextStore.MessageNode::messageId).collect(java.util.stream.Collectors.toSet());
        return model.summaries().stream().allMatch(summary -> authority.summaries().stream()
                        .anyMatch(value -> value.equals(summary)))
                && model.referenceIndex().values().stream().allMatch(reference -> authority.references().stream()
                        .anyMatch(value -> value.equals(reference)))
                && model.factIndex().entrySet().stream().allMatch(entry ->
                        entry.getKey().equals(entry.getValue().factId())
                                && activeIds.containsAll(entry.getValue().sourceIds()))
                && model.activePath().stream().allMatch(node -> authorityIds.contains(node.messageId()));
    }

    private static boolean samePath(
            List<SessionContextStore.MessageNode> left,
            List<SessionContextStore.MessageNode> right) {
        return left.size() == right.size()
                && java.util.stream.IntStream.range(0, left.size())
                .allMatch(index -> {
                    SessionContextStore.MessageNode a = left.get(index);
                    SessionContextStore.MessageNode b = right.get(index);
                    return Objects.equals(a.messageId(), b.messageId())
                            && Objects.equals(a.parentMessageId(), b.parentMessageId())
                            && Objects.equals(a.role(), b.role())
                            && Objects.equals(a.content(), b.content());
                });
    }
}
