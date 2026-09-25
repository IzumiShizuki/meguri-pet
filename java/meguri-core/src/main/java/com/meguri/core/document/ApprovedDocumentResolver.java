package com.meguri.core.document;

import com.meguri.core.resource.LocalResourcePathPolicy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves one explicitly selected document without accepting arbitrary paths. */
public final class ApprovedDocumentResolver {
    public static final int MAX_DOCUMENT_BYTES = 1_048_576;
    public static final int MAX_DOCUMENT_CHARACTERS = 200_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "csv", "json", "yaml", "yml");

    /** Parses only a well-formed document attachment marked for document reading. */
    public Optional<ApprovedDocument> resolveAttachment(Map<String, Object> attachment) {
        if (attachment == null
                || !"local_file_reference".equals(stringValue(attachment, "type"))
                || !"everything".equals(stringValue(attachment, "source"))
                || !"document_read".equals(stringValue(attachment, "content_access"))) {
            return Optional.empty();
        }
        return Optional.of(resolvePath(
                stringValue(attachment, "path"), stringValue(attachment, "reference_id")));
    }

    /** Revalidates a stored path and checks it still has the original opaque reference. */
    public ApprovedDocument resolvePath(String rawPath, String expectedReferenceId) {
        LocalResourcePathPolicy.ResolvedPath resolved = LocalResourcePathPolicy.resolve(rawPath, false)
                .orElseThrow(() -> new DocumentEditingException(
                        "The selected document is no longer safe or available."));
        String actualReferenceId = LocalResourcePathPolicy.stableId(resolved.path());
        if (!actualReferenceId.equals(expectedReferenceId)) {
            throw new DocumentEditingException("The selected document reference is invalid.");
        }
        long size = resolved.attributes().size();
        if (size < 0 || size > MAX_DOCUMENT_BYTES) {
            throw new DocumentEditingException("Documents must be no larger than 1 MB.");
        }
        Path path = resolved.path();
        ApprovedDocument.Kind kind = kindFor(path);
        byte[] source;
        try {
            source = Files.readAllBytes(path);
        } catch (IOException error) {
            throw new DocumentEditingException("The selected document could not be read.", error);
        }
        if (source.length != size) {
            throw new DocumentEditingException("The selected document changed while it was being read.");
        }
        String text = kind == ApprovedDocument.Kind.DOCX
                ? DocxDocumentCodec.extractText(path)
                : decodeText(source);
        if (text.length() > MAX_DOCUMENT_CHARACTERS) {
            throw new DocumentEditingException("Document text exceeds the supported content limit.");
        }
        String name = path.getFileName() == null ? "document" : path.getFileName().toString();
        return new ApprovedDocument(actualReferenceId, name, path, kind, sha256(source), text, source.length);
    }

    static ApprovedDocument.Kind kindFor(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (TEXT_EXTENSIONS.contains(extension)) return ApprovedDocument.Kind.TEXT;
        if ("docx".equals(extension)) return ApprovedDocument.Kind.DOCX;
        throw new DocumentEditingException(
                "Only TXT, Markdown, CSV, JSON, YAML, and DOCX documents can be read or edited.");
    }

    static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String decodeText(byte[] source) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (source.length >= 3 && (source[0] & 0xFF) == 0xEF
                && (source[1] & 0xFF) == 0xBB && (source[2] & 0xFF) == 0xBF) {
            offset = 3;
        } else if (source.length >= 2 && (source[0] & 0xFF) == 0xFF && (source[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (source.length >= 2 && (source[0] & 0xFF) == 0xFE && (source[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        try {
            String decoded = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source, offset, source.length - offset)).toString();
            if (decoded.indexOf('\u0000') >= 0) {
                throw new DocumentEditingException("The selected file is binary, not a supported text document.");
            }
            return decoded;
        } catch (CharacterCodingException error) {
            throw new DocumentEditingException("The selected text document is not valid UTF text.", error);
        }
    }

    private static String stringValue(Map<String, Object> attachment, String key) {
        Object value = attachment.get(key);
        return value instanceof String text ? text.trim() : "";
    }
}
