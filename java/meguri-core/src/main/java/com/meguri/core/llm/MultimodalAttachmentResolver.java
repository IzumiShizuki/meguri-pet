package com.meguri.core.llm;

import com.meguri.core.document.ApprovedDocument;
import com.meguri.core.document.ApprovedDocumentResolver;
import com.meguri.core.document.DocumentEditingException;
import com.meguri.core.resource.LocalResourcePathPolicy;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves only explicit, bounded user-approved attachments into model content. */
public final class MultimodalAttachmentResolver {
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");
    private static final int DEFAULT_MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private static final int DEFAULT_MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    private final int maxAttachmentBytes;
    private final int maxTotalBytes;
    private final ApprovedDocumentResolver documents = new ApprovedDocumentResolver();

    public MultimodalAttachmentResolver() {
        this(DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public MultimodalAttachmentResolver(int maxAttachmentBytes, int maxTotalBytes) {
        if (maxAttachmentBytes <= 0 || maxTotalBytes < maxAttachmentBytes) {
            throw new IllegalArgumentException("multimodal attachment limits are invalid");
        }
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    /** Returns model contents or fails before any provider call when a request is unsafe. */
    public List<Content> resolve(List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        List<Content> contents = new ArrayList<>();
        long totalBytes = 0;
        for (Map<String, Object> attachment : attachments) {
            String access = stringValue(attachment, "content_access");
            if ("document_read".equals(access)) {
                ApprovedDocument document;
                try {
                    document = documents.resolveAttachment(attachment).orElseThrow(
                            () -> new DocumentEditingException("Invalid approved document attachment."));
                } catch (DocumentEditingException error) {
                    throw new LlmProviderException(error.getMessage(), error);
                }
                totalBytes += document.byteSize();
                if (totalBytes > maxTotalBytes) {
                    throw new LlmProviderException("Document and multimodal attachments exceed the total size limit.");
                }
                contents.add(document.modelContent());
                continue;
            }
            if (!"multimodal_read".equals(access)) continue;
            ResolvedAttachment resolved = resolveOne(attachment);
            totalBytes += resolved.byteSize();
            if (totalBytes > maxTotalBytes) {
                throw new LlmProviderException("Multimodal attachments exceed the total size limit.");
            }
            contents.add(resolved.content());
        }
        return List.copyOf(contents);
    }

    private ResolvedAttachment resolveOne(Map<String, Object> attachment) {
        if (attachment == null) throw new LlmProviderException("Invalid multimodal attachment.");
        String type = stringValue(attachment, "type");
        if ("inline_attachment".equals(type)) return resolveInlineImage(attachment);
        if ("local_file_reference".equals(type)) return resolveLocalFile(attachment);
        throw new LlmProviderException("Unsupported multimodal attachment type.");
    }

    private ResolvedAttachment resolveInlineImage(Map<String, Object> attachment) {
        if (!"airi_inline".equals(stringValue(attachment, "source"))) {
            throw new LlmProviderException("Inline attachment source is not allowed.");
        }
        String mimeType = normalizedMime(stringValue(attachment, "mime_type"));
        if (!IMAGE_MIME_TYPES.contains(mimeType)) {
            throw new LlmProviderException("Only PNG, JPEG, GIF, and WebP images can be sent to the model.");
        }
        String dataUrl = stringValue(attachment, "data_url");
        String prefix = "data:" + mimeType + ";base64,";
        if (!dataUrl.startsWith(prefix)) {
            throw new LlmProviderException("Inline image data is malformed.");
        }
        String base64 = dataUrl.substring(prefix.length());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException error) {
            throw new LlmProviderException("Inline image data is malformed.", error);
        }
        verifyAttachmentSize(bytes.length);
        return new ResolvedAttachment(ImageContent.from(base64, mimeType), bytes.length);
    }

    private ResolvedAttachment resolveLocalFile(Map<String, Object> attachment) {
        if (!"everything".equals(stringValue(attachment, "source"))) {
            throw new LlmProviderException("Local file source is not allowed.");
        }
        LocalResourcePathPolicy.ResolvedPath resolved = LocalResourcePathPolicy
                .resolve(stringValue(attachment, "path"), false)
                .orElseThrow(() -> new LlmProviderException(
                        "The selected local file is no longer safe or available."));
        long size = resolved.attributes().size();
        verifyAttachmentSize(size);
        Path path = resolved.path();
        String mimeType = mimeTypeFor(path);
        if (IMAGE_MIME_TYPES.contains(mimeType)) {
            return new ResolvedAttachment(ImageContent.from(path, mimeType), size);
        }
        if ("application/pdf".equals(mimeType)) {
            return new ResolvedAttachment(PdfFileContent.from(path, mimeType), size);
        }
        throw new LlmProviderException("Only PNG, JPEG, GIF, WebP, and PDF files can be sent to the model.");
    }

    private void verifyAttachmentSize(long size) {
        if (size <= 0 || size > maxAttachmentBytes) {
            throw new LlmProviderException("Each multimodal attachment must be within the configured size limit.");
        }
    }

    private static String mimeTypeFor(Path path) {
        String value = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        if (value.endsWith(".gif")) return "image/gif";
        if (value.endsWith(".webp")) return "image/webp";
        if (value.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String normalizedMime(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringValue(Map<String, Object> value, String key) {
        if (value == null) return "";
        Object raw = value.get(key);
        return raw instanceof String text ? text : "";
    }

    private record ResolvedAttachment(Content content, long byteSize) { }
}
