package com.meguri.core.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds ephemeral, path-free LLM context from explicitly selected resources. */
public final class LocalResourcePromptContext {
    private static final int MAX_REFERENCES = 5;
    private static final int MAX_NAME_LENGTH = 160;
    private static final Pattern SAFE_KIND = Pattern.compile("[a-z0-9_-]{1,32}");

    private LocalResourcePromptContext() { }

    public static List<String> from(List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        List<String> context = new ArrayList<>(Math.min(MAX_REFERENCES, attachments.size()));
        for (Map<String, Object> attachment : attachments) {
            if (context.size() >= MAX_REFERENCES) break;
            if (!accepted(attachment)) continue;
            String name = oneLine(stringValue(attachment.get("name")), MAX_NAME_LENGTH);
            if (name.isBlank()) continue;
            String kind = stringValue(attachment.get("kind")).trim().toLowerCase(Locale.ROOT);
            if (!SAFE_KIND.matcher(kind).matches()) kind = "file";
            String access = stringValue(attachment.get("content_access"));
            String description = switch (access) {
                case "multimodal_read" -> "user-approved content is attached; do not infer access to any other local file";
                case "document_read" -> "user-approved document content is attached; propose edits but never claim the file was written";
                default -> "untrusted metadata only; do not claim knowledge of file contents";
            };
            context.add("local_resource_reference (" + description + "): name=\""
                    + quoted(name) + "\", kind=\"" + quoted(kind)
                    + "\", content_access=\"" + quoted(access) + "\".");
        }
        return List.copyOf(context);
    }

    public static boolean hasAcceptedReference(List<Map<String, Object>> attachments) {
        return !from(attachments).isEmpty();
    }

    private static boolean accepted(Map<String, Object> attachment) {
        return attachment != null
                && "local_file_reference".equals(stringValue(attachment.get("type")))
                && "everything".equals(stringValue(attachment.get("source")))
                && ("not_read".equals(stringValue(attachment.get("content_access")))
                || "multimodal_read".equals(stringValue(attachment.get("content_access")))
                || "document_read".equals(stringValue(attachment.get("content_access"))));
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String oneLine(String value, int limit) {
        String normalized = value.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s{2,}", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static String quoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
