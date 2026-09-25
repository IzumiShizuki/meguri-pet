package com.meguri.core.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Applies a one-time, digest-bound preview after the desktop user confirms it. */
public final class DocumentEditService {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final DocumentEditPreviewStore previews;
    private final ApprovedDocumentResolver documents;

    public DocumentEditService(DocumentEditPreviewStore previews) {
        this.previews = previews == null ? DocumentEditPreviewStore.shared() : previews;
        this.documents = new ApprovedDocumentResolver();
    }

    public ApplyResult apply(String token) {
        DocumentEditPreviewStore.Preview preview = previews.find(token)
                .orElseThrow(() -> new DocumentEditingException(
                        "This document edit preview has expired or was already used."));
        ApprovedDocument original = preview.document();
        ApprovedDocument current = documents.resolvePath(original.path().toString(), original.referenceId());
        if (!current.sha256().equalsIgnoreCase(original.sha256())) {
            throw new DocumentEditingException("The document changed after review. Please ask for a new preview.");
        }
        byte[] updated = updatedBytes(current, preview.plan());
        if (updated.length > ApprovedDocumentResolver.MAX_DOCUMENT_BYTES) {
            throw new DocumentEditingException("The proposed document would exceed the 1 MB size limit.");
        }
        // Detect a write race after DOCX transformation and before backup/write.
        try {
            byte[] reread = Files.readAllBytes(current.path());
            if (!ApprovedDocumentResolver.sha256(reread).equalsIgnoreCase(original.sha256())) {
                throw new DocumentEditingException("The document changed while the edit was being prepared.");
            }
        } catch (IOException error) {
            throw new DocumentEditingException("The document could not be rechecked before editing.", error);
        }
        Path backup = backupPath(current.path());
        Path temporary = null;
        try {
            Files.copy(current.path(), backup, StandardCopyOption.COPY_ATTRIBUTES);
            temporary = Files.createTempFile(current.path().getParent(), ".meguri-edit-", ".tmp");
            Files.write(temporary, updated);
            try {
                Files.move(temporary, current.path(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new DocumentEditingException(
                        "This folder does not support safe atomic document replacement.", error);
            }
            previews.remove(token);
            return new ApplyResult(current.name(), current.path().toString(), backup.toString());
        } catch (IOException error) {
            throw new DocumentEditingException("The document could not be written safely; the original was kept.", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private static byte[] updatedBytes(ApprovedDocument document, DocumentEditPlan plan) {
        if (document.kind() == ApprovedDocument.Kind.DOCX) {
            return DocxDocumentCodec.replaceText(document.path(), plan.replacements());
        }
        if (!plan.isFullReplacement()) {
            throw new DocumentEditingException("Text documents require a complete replacement preview.");
        }
        try {
            byte[] source = Files.readAllBytes(document.path());
            return encodeLikeSource(plan.replacementText(), source);
        } catch (IOException error) {
            throw new DocumentEditingException("The text document could not be prepared for editing.", error);
        }
    }

    private static byte[] encodeLikeSource(String replacement, byte[] source) {
        if (source.length >= 2 && (source[0] & 0xFF) == 0xFF && (source[1] & 0xFF) == 0xFE) {
            byte[] body = replacement.getBytes(StandardCharsets.UTF_16LE);
            return prepend(new byte[] {(byte) 0xFF, (byte) 0xFE}, body);
        }
        if (source.length >= 2 && (source[0] & 0xFF) == 0xFE && (source[1] & 0xFF) == 0xFF) {
            byte[] body = replacement.getBytes(StandardCharsets.UTF_16BE);
            return prepend(new byte[] {(byte) 0xFE, (byte) 0xFF}, body);
        }
        byte[] body = replacement.getBytes(StandardCharsets.UTF_8);
        if (source.length >= 3 && (source[0] & 0xFF) == 0xEF
                && (source[1] & 0xFF) == 0xBB && (source[2] & 0xFF) == 0xBF) {
            return prepend(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, body);
        }
        return body;
    }

    private static byte[] prepend(byte[] prefix, byte[] body) {
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    private static Path backupPath(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String extension = dot <= 0 ? "" : name.substring(dot);
        String suffix = ".meguri-backup-" + BACKUP_TIME.format(Instant.now());
        return source.resolveSibling(base + suffix + extension);
    }

    public record ApplyResult(String documentName, String documentPath, String backupPath) {
        public Map<String, Object> toWire() {
            return Map.of("applied", true, "document_name", documentName,
                    "document_path", documentPath, "backup_path", backupPath);
        }
    }
}
