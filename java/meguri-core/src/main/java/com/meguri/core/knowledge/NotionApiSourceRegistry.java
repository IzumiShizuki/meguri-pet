package com.meguri.core.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Authoritative Notion allowlist snapshot backed by Retrieve page and recursive
 * Retrieve block children calls. Any incomplete traversal fails closed.
 */
public final class NotionApiSourceRegistry implements SourceRegistry {
    public static final String DEFAULT_NOTION_VERSION = "2026-03-11";
    private static final Pattern NOTION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final String sourceId;
    private final KnowledgeAcl acl;
    private final Set<String> allowlist;
    private final NotionApiCredentials credentials;
    private final NotionHttpPort http;
    private final ObjectMapper mapper;
    private final NotionSyncLimits limits;
    private final String notionVersion;
    private final LongSupplier nanoTime;

    public NotionApiSourceRegistry(
            String sourceId,
            KnowledgeAcl acl,
            Set<String> allowlist,
            NotionApiCredentials credentials,
            NotionHttpPort http,
            ObjectMapper mapper,
            NotionSyncLimits limits) {
        this(sourceId, acl, allowlist, credentials, http, mapper, limits,
                DEFAULT_NOTION_VERSION, System::nanoTime);
    }

    NotionApiSourceRegistry(
            String sourceId,
            KnowledgeAcl acl,
            Set<String> allowlist,
            NotionApiCredentials credentials,
            NotionHttpPort http,
            ObjectMapper mapper,
            NotionSyncLimits limits,
            String notionVersion,
            LongSupplier nanoTime) {
        this.sourceId = required(sourceId, "sourceId");
        this.acl = Objects.requireNonNull(acl, "acl");
        this.allowlist = normalizedAllowlist(allowlist);
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.notionVersion = required(notionVersion, "notionVersion");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public List<SourcePage> scan() {
        return scan(Map.of());
    }

    /**
     * Scans page metadata on every run, but reuses a caller-owned page snapshot
     * when Notion confirms that its last-edited timestamp is unchanged.
     */
    public List<SourcePage> scan(Map<String, SourcePage> reusableSnapshot) {
        Map<String, SourcePage> snapshot = Map.copyOf(
                Objects.requireNonNull(reusableSnapshot, "reusableSnapshot"));
        long deadline = deadline(nanoTime.getAsLong(), limits.timeout());
        BlockCounter blocks = new BlockCounter();
        ArrayList<SourcePage> pages = new ArrayList<>(allowlist.size());
        for (String pageId : allowlist) {
            requireTime(deadline);
            pages.add(retrievePage(pageId, snapshot.get(pageId), blocks, deadline));
        }
        return List.copyOf(pages);
    }

    private SourcePage retrievePage(
            String pageId, SourcePage reusablePage, BlockCounter blocks, long deadline) {
        NotionHttpResponse response = fetch(
                "/v1/pages/" + safeId(pageId), Map.of(), deadline);
        if (unavailable(response.statusCode())) return tombstone(pageId, pageId, Instant.EPOCH);
        requireSuccess(response, "Retrieve page");
        JsonNode page = parse(response.body(), "Retrieve page");
        Instant lastEdited = requiredInstant(page, "last_edited_time");
        String title = pageTitle(page, pageId);
        String sourceUri = "notion://page/" + pageId;
        String canonicalUri = page.path("url").asText("").strip();
        if (canonicalUri.isBlank()) canonicalUri = sourceUri;
        String sourceParentId = sourceParentId(page.path("parent"));
        if (page.path("archived").asBoolean(false) || page.path("in_trash").asBoolean(false)) {
            return tombstone(pageId, title, lastEdited);
        }
        if (reusablePage != null
                && !reusablePage.tombstone()
                && reusablePage.sourceId().equals(sourceId)
                && reusablePage.pageId().equals(pageId)
                && reusablePage.acl().equals(acl)
                && reusablePage.lastEditedTime().equals(lastEdited)
                && reusablePage.title().equals(title)
                && reusablePage.metadata().sourceUri().equals(sourceUri)
                && reusablePage.metadata().canonicalUri().equals(canonicalUri)
                && Objects.equals(
                        reusablePage.metadata().sourceParentId(), sourceParentId)) {
            return reusablePage;
        }

        try {
            ArrayList<String> lines = new ArrayList<>();
            TreeSet<String> linkedPageIds = new TreeSet<>();
            retrieveChildren(
                    pageId, 0, lines, linkedPageIds, blocks, deadline);
            // Preserve block boundaries; the projector maps headings to section parents.
            String content = String.join("\n\n", lines);
            linkedPageIds.remove(pageId);
            KnowledgeSourceMetadata metadata = metadata(
                    page, pageId, content, linkedPageIds);
            return new SourcePage(
                    sourceId, pageId, title, content, sha256(content),
                    lastEdited, acl, false, false, metadata);
        } catch (PageUnavailable ignored) {
            return tombstone(pageId, title, lastEdited);
        }
    }

    private void retrieveChildren(
            String blockId,
            int depth,
            List<String> lines,
            Set<String> linkedPageIds,
            BlockCounter blocks,
            long deadline) {
        String cursor = null;
        HashSet<String> seenCursors = new HashSet<>();
        do {
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("page_size", "100");
            if (cursor != null) query.put("start_cursor", cursor);
            NotionHttpResponse response = fetch(
                    "/v1/blocks/" + safeId(blockId) + "/children", query, deadline);
            if (unavailable(response.statusCode())) throw new PageUnavailable();
            requireSuccess(response, "Retrieve block children");
            JsonNode payload = parse(response.body(), "Retrieve block children");
            JsonNode results = payload.get("results");
            if (results == null || !results.isArray()) {
                throw new KnowledgeSourceSyncException(
                        "Notion Retrieve block children response has no results array");
            }
            for (JsonNode block : results) {
                blocks.increment(limits.maxBlocks());
                collectLinkedPageIds(block, linkedPageIds);
                String text = blockText(block);
                if (!text.isBlank()) lines.add("  ".repeat(depth) + text);
                if (block.path("has_children").asBoolean(false)) {
                    if (depth >= limits.maxDepth()) {
                        throw new KnowledgeSourceSyncException("Notion block depth limit exceeded");
                    }
                    String childId = requiredText(block, "id", "Notion child block id");
                    retrieveChildren(
                            childId, depth + 1, lines, linkedPageIds,
                            blocks, deadline);
                }
            }
            boolean hasMore = payload.path("has_more").asBoolean(false);
            if (!hasMore) return;
            cursor = requiredText(payload, "next_cursor", "Notion pagination cursor");
            if (!seenCursors.add(cursor)) {
                throw new KnowledgeSourceSyncException("Notion pagination cursor repeated");
            }
        } while (true);
    }

    private NotionHttpResponse fetch(String path, Map<String, String> query, long deadline) {
        Duration remaining = remaining(deadline);
        NotionHttpRequest request = new NotionHttpRequest(
                path, query, notionVersion, credentials, remaining);
        NotionHttpResponse response;
        try {
            response = Objects.requireNonNull(http.execute(request), "Notion HTTP response");
        } catch (KnowledgeSourceSyncException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new KnowledgeSourceSyncException("Notion HTTP transport failed");
        }
        requireTime(deadline);
        return response;
    }

    private void requireSuccess(NotionHttpResponse response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new KnowledgeSourceSyncException(
                    operation + " failed with HTTP " + response.statusCode());
        }
    }

    private JsonNode parse(String body, String operation) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException error) {
            throw new KnowledgeSourceSyncException(operation + " returned invalid JSON");
        }
    }

    private static String pageTitle(JsonNode page, String fallback) {
        JsonNode properties = page.path("properties");
        if (properties.isObject()) {
            for (JsonNode property : properties) {
                if (!"title".equals(property.path("type").asText())) continue;
                String title = richText(property.path("title"));
                if (!title.isBlank()) return title;
            }
        }
        return fallback;
    }

    private static String blockText(JsonNode block) {
        String type = block.path("type").asText("");
        JsonNode value = block.path(type);
        String text = richText(value.path("rich_text"));
        if (text.isBlank() && value.path("title").isTextual()) text = value.path("title").asText();
        if (text.isBlank() && value.path("url").isTextual()) text = value.path("url").asText();
        text = text.strip();
        if (text.isBlank()) return text;
        return switch (type) {
            case "heading_1" -> "# " + text;
            case "heading_2" -> "## " + text;
            case "heading_3" -> "### " + text;
            default -> text;
        };
    }

    private static String richText(JsonNode values) {
        if (!values.isArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonNode value : values) {
            String text = value.path("plain_text").asText("");
            if (text.isEmpty()) text = value.path("text").path("content").asText("");
            result.append(text);
        }
        return result.toString();
    }

    private KnowledgeSourceMetadata metadata(
            JsonNode page,
            String pageId,
            String content,
            Set<String> linkedPageIds) {
        String sourceUri = "notion://page/" + pageId;
        String canonicalUri = page.path("url").asText("").strip();
        if (canonicalUri.isBlank()) canonicalUri = sourceUri;
        return new KnowledgeSourceMetadata(
                sourceUri,
                canonicalUri,
                KnowledgeSourceMetadata.detectLanguage(content),
                sourceId,
                sourceParentId(page.path("parent")),
                List.copyOf(linkedPageIds));
    }

    private static String sourceParentId(JsonNode parent) {
        if (!parent.isObject()) return null;
        for (String field : List.of(
                "page_id", "database_id", "data_source_id", "block_id")) {
            JsonNode value = parent.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return safeId(value.asText());
            }
        }
        return null;
    }

    private static void collectLinkedPageIds(
            JsonNode node, Set<String> linkedPageIds) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectLinkedPageIds(child, linkedPageIds));
            return;
        }
        if (!node.isObject()) return;
        if ("page".equals(node.path("type").asText())) {
            JsonNode mentionedId = node.path("page").get("id");
            if (mentionedId != null
                    && mentionedId.isTextual()
                    && !mentionedId.asText().isBlank()) {
                linkedPageIds.add(safeId(mentionedId.asText()));
            }
        }
        node.fields().forEachRemaining(entry -> {
            if ("page_id".equals(entry.getKey())
                    && entry.getValue().isTextual()
                    && !entry.getValue().asText().isBlank()) {
                linkedPageIds.add(safeId(entry.getValue().asText()));
            }
            collectLinkedPageIds(entry.getValue(), linkedPageIds);
        });
    }

    private SourcePage tombstone(String pageId, String title, Instant lastEdited) {
        return new SourcePage(
                sourceId, pageId, title, "", sha256(""), lastEdited, acl, true, false);
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - nanoTime.getAsLong();
        if (nanos <= 0) throw new KnowledgeSourceSyncException("Notion source sync timeout exceeded");
        return Duration.ofNanos(nanos);
    }

    private void requireTime(long deadline) {
        if (deadline - nanoTime.getAsLong() <= 0) {
            throw new KnowledgeSourceSyncException("Notion source sync timeout exceeded");
        }
    }

    private static long deadline(long start, Duration timeout) {
        long nanos;
        try {
            nanos = timeout.toNanos();
            return Math.addExact(start, nanos);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        String value = requiredText(node, field, "Notion " + field);
        try {
            return Instant.parse(value);
        } catch (RuntimeException error) {
            throw new KnowledgeSourceSyncException("Notion " + field + " is invalid");
        }
    }

    private static String requiredText(JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new KnowledgeSourceSyncException(label + " is missing");
        }
        return value.asText();
    }

    private static boolean unavailable(int status) {
        return status == 403 || status == 404 || status == 410;
    }

    private static Set<String> normalizedAllowlist(Set<String> values) {
        Objects.requireNonNull(values, "allowlist");
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) result.add(safeId(value));
        return Collections.unmodifiableSet(result);
    }

    private static String safeId(String value) {
        String id = required(value, "Notion id");
        if (!NOTION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Notion id contains unsupported characters");
        }
        return id;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class BlockCounter {
        private int value;

        void increment(int limit) {
            value++;
            if (value > limit) {
                throw new KnowledgeSourceSyncException("Notion block limit exceeded");
            }
        }
    }

    private static final class PageUnavailable extends RuntimeException {
    }
}
