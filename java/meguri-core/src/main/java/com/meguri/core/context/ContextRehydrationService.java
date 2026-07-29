package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.ArrayList;
import java.util.List;

/** Restores a bounded local window around QUOTE/TOPIC_LINK sources without reviving a sibling branch. */
public final class ContextRehydrationService {
    private final int messagesBefore;
    private final int messagesAfter;

    public ContextRehydrationService() {
        this(6, 4);
    }

    public ContextRehydrationService(int messagesBefore, int messagesAfter) {
        this.messagesBefore = Math.max(0, messagesBefore);
        this.messagesAfter = Math.max(0, messagesAfter);
    }

    public List<RehydratedWindow> rehydrate(
            SessionContextStore.GraphSnapshot graph,
            List<SessionContextStore.ContextReference> references) {
        List<SessionContextStore.MessageNode> activePath = graph.activePath();
        List<RehydratedWindow> result = new ArrayList<>();
        for (SessionContextStore.ContextReference reference : references) {
            if (reference.type() == SessionContextStore.ReferenceType.RESUME_FROM) continue;
            int sourceIndex = indexOf(activePath, reference.sourceMessageId());
            if (sourceIndex < 0) continue;
            SessionContextStore.MessageNode source = activePath.get(sourceIndex);
            int from = Math.max(0, sourceIndex - messagesBefore);
            int to = Math.min(activePath.size(), sourceIndex + messagesAfter + 1);
            List<SessionContextStore.MessageNode> window =
                    new ArrayList<>(activePath.subList(from, to));
            String exact = reference.snapshotText() == null ? source.content() : reference.snapshotText();
            result.add(new RehydratedWindow(reference.referenceId(), reference.type(),
                    source.messageId(), exact, List.copyOf(window)));
        }
        return List.copyOf(result);
    }

    private static int indexOf(
            List<SessionContextStore.MessageNode> activePath, String messageId) {
        for (int index = 0; index < activePath.size(); index++) {
            if (activePath.get(index).messageId().equals(messageId)) {
                return index;
            }
        }
        return -1;
    }

    public record RehydratedWindow(
            String referenceId,
            SessionContextStore.ReferenceType type,
            String sourceMessageId,
            String exactSnapshot,
            List<SessionContextStore.MessageNode> messages) { }
}
