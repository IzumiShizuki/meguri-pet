package com.meguri.core.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedDocumentEditingTest {
    private Path root;

    @BeforeEach
    void createRoot() throws IOException {
        // JUnit's default TempDir is beneath AppData, intentionally denied by the local-file policy.
        root = Files.createTempDirectory(Path.of("D:\\program"), "meguri-document-test-");
    }

    @AfterEach
    void deleteRoot() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); }
                });
            }
        }
    }

    @Test
    void resolvesOnlyExplicitSupportedTextDocuments() throws Exception {
        Path text = Files.writeString(root.resolve("notes.md"), "# Title\nHello");
        String reference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(text.toRealPath());
        ApprovedDocument document = new ApprovedDocumentResolver().resolveAttachment(Map.of(
                "type", "local_file_reference", "source", "everything", "content_access", "document_read",
                "reference_id", reference, "path", text.toString())).orElseThrow();

        assertEquals(ApprovedDocument.Kind.TEXT, document.kind());
        assertEquals("# Title\nHello", document.text());
        assertTrue(document.modelContent().text().contains("reference_id: " + reference));
        assertFalse(document.modelContent().text().contains(text.toString()));
    }

    @Test
    void rejectsUnsafeReferencesAndInvalidTextEncoding() throws Exception {
        Path unsupported = Files.write(root.resolve("tool.exe"), new byte[] {1, 2});
        String reference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(unsupported.toRealPath());
        assertThrows(DocumentEditingException.class, () -> new ApprovedDocumentResolver().resolveAttachment(Map.of(
                "type", "local_file_reference", "source", "everything", "content_access", "document_read",
                "reference_id", reference, "path", unsupported.toString())));

        Path invalid = Files.write(root.resolve("invalid.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
        String invalidReference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(invalid.toRealPath());
        assertThrows(DocumentEditingException.class, () -> new ApprovedDocumentResolver().resolvePath(
                invalid.toString(), invalidReference));
    }

    @Test
    void storesModelTextReplacementUntilExplicitConfirmationAndCreatesBackup() throws Exception {
        Path text = Files.writeString(root.resolve("todo.txt"), "before");
        String reference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(text.toRealPath());
        Map<String, Object> attachment = Map.of("type", "local_file_reference", "source", "everything",
                "content_access", "document_read", "reference_id", reference, "path", text.toString());
        ApprovedDocument document = new ApprovedDocumentResolver().resolveAttachment(attachment).orElseThrow();
        String reply = "Done.\n```meguri-document-edit\n{\"reference_id\":\"" + reference
                + "\",\"sha256\":\"" + document.sha256()
                + "\",\"summary\":\"replace the note\",\"replacement_text\":\"after\"}\n```";
        DocumentEditPreviewStore previews = new DocumentEditPreviewStore(Duration.ofMinutes(5));
        List<Map<String, Object>> proposals = new ModelDocumentEditProposalResolver(
                new ObjectMapper(), previews).resolve(reply, List.of(attachment));

        assertEquals(1, proposals.size());
        assertEquals("before", Files.readString(text));
        String token = (String) proposals.getFirst().get("preview_token");
        assertNotNull(token);
        DocumentEditService.ApplyResult result = new DocumentEditService(previews).apply(token);

        assertEquals("after", Files.readString(text));
        assertEquals("before", Files.readString(Path.of(result.backupPath())));
        assertTrue(Files.exists(Path.of(result.backupPath())));
        assertThrows(DocumentEditingException.class, () -> new DocumentEditService(previews).apply(token));
    }

    @Test
    void refusesAStaleTextPreviewBeforeWriting() throws Exception {
        Path text = Files.writeString(root.resolve("stale.txt"), "before");
        String reference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(text.toRealPath());
        ApprovedDocument document = new ApprovedDocumentResolver().resolvePath(text.toString(), reference);
        DocumentEditPreviewStore previews = new DocumentEditPreviewStore(Duration.ofMinutes(5));
        var preview = previews.create(document, new DocumentEditPlan("change", "after", List.of()));
        Files.writeString(text, "changed elsewhere");

        assertThrows(DocumentEditingException.class, () -> new DocumentEditService(previews).apply(preview.token()));
        assertEquals("changed elsewhere", Files.readString(text));
    }

    @Test
    void decliningAPreviewLeavesTheDocumentUntouched() throws Exception {
        Path text = Files.writeString(root.resolve("decline.txt"), "before");
        String reference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(text.toRealPath());
        ApprovedDocument document = new ApprovedDocumentResolver().resolvePath(text.toString(), reference);
        DocumentEditPreviewStore previews = new DocumentEditPreviewStore(Duration.ofMinutes(5));
        var preview = previews.create(document, new DocumentEditPlan("change", "after", List.of()));

        previews.remove(preview.token());

        assertThrows(DocumentEditingException.class, () -> new DocumentEditService(previews).apply(preview.token()));
        assertEquals("before", Files.readString(text));
    }

    @Test
    void readsAndEditsASimpleDocxWithoutRewritingOtherPackageEntries() throws Exception {
        Path docx = root.resolve("letter.docx");
        createDocx(docx, "Hello Beta");
        String reference = com.meguri.core.resource.LocalResourcePathPolicy.stableId(docx.toRealPath());
        ApprovedDocument document = new ApprovedDocumentResolver().resolvePath(docx.toString(), reference);
        assertEquals(ApprovedDocument.Kind.DOCX, document.kind());
        assertEquals("Hello Beta", document.text());

        DocumentEditPreviewStore previews = new DocumentEditPreviewStore(Duration.ofMinutes(5));
        var preview = previews.create(document, new DocumentEditPlan("rename addressee", null,
                List.of(new DocumentEditPlan.Replacement("Beta", "Gamma"))));
        DocumentEditService.ApplyResult applied = new DocumentEditService(previews).apply(preview.token());

        assertEquals("Hello Gamma", DocxDocumentCodec.extractText(docx));
        assertTrue(Files.exists(Path.of(applied.backupPath())));
        assertEquals("Hello Beta", DocxDocumentCodec.extractText(Path.of(applied.backupPath())));
    }

    private static void createDocx(Path path, String text) throws IOException {
        try (OutputStream raw = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>").getBytes());
            zip.closeEntry();
        }
    }
}
