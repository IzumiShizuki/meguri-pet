package com.meguri.core.document;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory, one-time confirmation tokens for model-proposed local edits. */
public final class DocumentEditPreviewStore {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final DocumentEditPreviewStore SHARED = new DocumentEditPreviewStore(DEFAULT_TTL);

    private final Duration ttl;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();

    public DocumentEditPreviewStore(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("preview ttl must be positive");
        }
        this.ttl = ttl;
    }

    /** Shared only within the local Core process; tokens vanish on restart. */
    public static DocumentEditPreviewStore shared() {
        return SHARED;
    }

    public Preview create(ApprovedDocument document, DocumentEditPlan plan) {
        pruneExpired();
        String token = UUID.randomUUID().toString();
        Preview preview = new Preview(token, document, plan, Instant.now().plus(ttl));
        previews.put(token, preview);
        return preview;
    }

    public Optional<Preview> find(String token) {
        Preview preview = previews.get(token);
        if (preview == null) return Optional.empty();
        if (preview.expiresAt().isAfter(Instant.now())) return Optional.of(preview);
        previews.remove(token, preview);
        return Optional.empty();
    }

    public void remove(String token) {
        if (token != null) previews.remove(token);
    }

    /** Supplies the desktop's recoverable confirmation bubbles without exposing paths. */
    public List<Map<String, Object>> pendingEventData() {
        pruneExpired();
        return previews.values().stream()
                .sorted(java.util.Comparator.comparing(Preview::expiresAt))
                .map(Preview::eventData)
                .toList();
    }

    private void pruneExpired() {
        Instant now = Instant.now();
        previews.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    public record Preview(
            String token,
            ApprovedDocument document,
            DocumentEditPlan plan,
            Instant expiresAt) {
        public Map<String, Object> eventData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("preview_token", token);
            data.put("reference_id", document.referenceId());
            data.put("document_name", document.name());
            data.put("document_kind", document.kind().wireName());
            data.put("summary", plan.summary());
            data.put("expires_at", expiresAt.toString());
            if (plan.isFullReplacement()) {
                data.put("preview", Map.of(
                        "mode", "full_replacement",
                        "before", clipped(document.text()),
                        "after", clipped(plan.replacementText()),
                        "truncated", document.text().length() > 4_000
                                || plan.replacementText().length() > 4_000));
            } else {
                data.put("preview", Map.of(
                        "mode", "exact_replacements",
                        "replacements", plan.replacements().stream()
                                .map(item -> Map.of("find", clipped(item.find()),
                                        "replace", clipped(item.replace())))
                                .toList()));
            }
            return Map.copyOf(data);
        }

        private static String clipped(String value) {
            return value.length() <= 4_000 ? value : value.substring(0, 4_000) + "\n…";
        }
    }
}
