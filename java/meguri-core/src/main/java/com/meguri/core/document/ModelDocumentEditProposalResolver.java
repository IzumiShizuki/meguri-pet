package com.meguri.core.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts one fenced, model-declared plan into a locally confirmable preview. */
public final class ModelDocumentEditProposalResolver {
    private static final Pattern FENCED_PROPOSAL = Pattern.compile(
            "```meguri-document-edit\\s*(\\{.*?})\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper;
    private final ApprovedDocumentResolver documents;
    private final DocumentEditPreviewStore previews;

    public ModelDocumentEditProposalResolver(ObjectMapper mapper, DocumentEditPreviewStore previews) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.documents = new ApprovedDocumentResolver();
        this.previews = previews == null ? DocumentEditPreviewStore.shared() : previews;
    }

    /** Invalid model markup is ordinary reply text, never a failed turn or write action. */
    public List<Map<String, Object>> resolve(String reply, List<Map<String, Object>> attachments) {
        if (reply == null || reply.isBlank() || attachments == null || attachments.isEmpty()) return List.of();
        List<ApprovedDocument> approved = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            try {
                documents.resolveAttachment(attachment).ifPresent(approved::add);
            } catch (DocumentEditingException ignored) {
                // The primary attachment resolver reports unsafe document input before provider invocation.
            }
        }
        if (approved.isEmpty()) return List.of();
        Matcher matcher = FENCED_PROPOSAL.matcher(reply);
        if (!matcher.find()) return List.of();
        try {
            JsonNode node = mapper.readTree(matcher.group(1));
            String referenceId = text(node, "reference_id");
            String digest = text(node, "sha256");
            ApprovedDocument document = approved.stream()
                    .filter(item -> item.referenceId().equals(referenceId)
                            && item.sha256().equalsIgnoreCase(digest))
                    .findFirst().orElse(null);
            if (document == null) return List.of();
            DocumentEditPlan plan = plan(node, document.kind());
            return List.of(previews.create(document, plan).eventData());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static DocumentEditPlan plan(JsonNode node, ApprovedDocument.Kind kind) {
        String summary = text(node, "summary");
        JsonNode replacementText = node.get("replacement_text");
        JsonNode replacements = node.get("replacements");
        if (kind == ApprovedDocument.Kind.TEXT && replacementText != null && replacementText.isTextual()
                && (replacements == null || replacements.isEmpty())) {
            String replacement = replacementText.textValue();
            if (replacement.length() > ApprovedDocumentResolver.MAX_DOCUMENT_CHARACTERS) {
                throw new IllegalArgumentException("replacement is too long");
            }
            return new DocumentEditPlan(summary, replacement, List.of());
        }
        if (kind == ApprovedDocument.Kind.DOCX && replacements != null && replacements.isArray()
                && (replacementText == null || replacementText.isNull())) {
            List<DocumentEditPlan.Replacement> operations = new ArrayList<>();
            for (JsonNode replacement : replacements) {
                operations.add(new DocumentEditPlan.Replacement(
                        text(replacement, "find"), text(replacement, "replace")));
            }
            return new DocumentEditPlan(summary, null, operations);
        }
        throw new IllegalArgumentException("proposal type does not match document type");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }
}
