package com.meguri.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic Notion boundary; a network SDK can populate the same page snapshots later. */
public final class NotionSourceAdapter implements SourceRegistry {
    private final String sourceId;
    private final KnowledgeAcl acl;
    private final Map<String, NotionPage> pages = new ConcurrentHashMap<>();
    private Set<String> allowlist;
    private Set<String> previousAllowlist;

    public NotionSourceAdapter(String sourceId, KnowledgeAcl acl,
                               Collection<NotionPage> pages, Set<String> allowlist) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId must not be blank");
        this.sourceId = sourceId.trim();
        this.acl = java.util.Objects.requireNonNull(acl, "acl");
        for (NotionPage page : pages) this.pages.put(page.pageId(), page);
        this.allowlist = normalized(allowlist);
        this.previousAllowlist = Set.of();
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public synchronized java.util.List<SourcePage> scan() {
        Set<String> candidates = new HashSet<>(allowlist);
        candidates.addAll(previousAllowlist);
        ArrayList<SourcePage> result = new ArrayList<>();
        for (String pageId : candidates) {
            NotionPage page = pages.get(pageId);
            boolean allowed = allowlist.contains(pageId);
            boolean tombstone = !allowed || page == null || page.deleted();
            Instant edited = page == null ? Instant.EPOCH : page.lastEditedTime();
            String title = page == null ? "Removed Notion page " + pageId : page.title();
            String content = tombstone || page == null ? "" : page.content();
            result.add(new SourcePage(
                    sourceId, pageId, title, content, sha256(content), edited, acl,
                    tombstone, page != null && page.secretMode()));
        }
        previousAllowlist = Set.copyOf(allowlist);
        result.sort(Comparator.comparing(SourcePage::pageId));
        return java.util.List.copyOf(result);
    }

    public void put(NotionPage page) {
        pages.put(page.pageId(), page);
    }

    public synchronized void setAllowlist(Set<String> pageIds) {
        allowlist = normalized(pageIds);
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null) throw new IllegalArgumentException("allowlist must not be null");
        HashSet<String> result = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("allowlist page id is blank");
            result.add(value.trim());
        }
        return Set.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
