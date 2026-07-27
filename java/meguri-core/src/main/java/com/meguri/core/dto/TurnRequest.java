package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.harness.retrieval.RetrievalMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Inbound turn contract. Unknown adapter fields are intentionally ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TurnRequest {
    private final String userId;
    private final String clientId;
    private final String sessionId;
    private final String parentSessionId;
    private final String message;
    private final List<Map<String, Object>> attachments;
    private final ClientCapabilities clientCapabilities;
    private final String optionalScreenContextId;
    private final Relationship relationshipProfile;
    private final boolean formalMemoryAllowed;
    private final boolean trainingMode;
    private final String replyFormat;
    private final RetrievalMode retrievalMode;

    public TurnRequest(String userId, String clientId, String sessionId, String message) {
        this(userId, clientId, sessionId, null, message, List.of(), new ClientCapabilities(), null, null, false, false);
    }

    public TurnRequest(String userId, String clientId, String sessionId, String message,
                       RetrievalMode retrievalMode) {
        this(userId, clientId, sessionId, null, message, List.of(), new ClientCapabilities(), null, null,
                false, false, "default", retrievalMode);
    }

    public TurnRequest(String userId, String clientId, String sessionId, String message,
                       List<Map<String, Object>> attachments,
                       ClientCapabilities clientCapabilities,
                       String optionalScreenContextId,
                       Relationship relationshipProfile,
                       boolean formalMemoryAllowed) {
        this(userId, clientId, sessionId, null, message, attachments, clientCapabilities, optionalScreenContextId, relationshipProfile, formalMemoryAllowed, false);
    }

    public TurnRequest(String userId, String clientId, String sessionId, String parentSessionId, String message,
                       List<Map<String, Object>> attachments,
                       ClientCapabilities clientCapabilities,
                       String optionalScreenContextId,
                       Relationship relationshipProfile,
                       boolean formalMemoryAllowed) {
        this(userId, clientId, sessionId, parentSessionId, message, attachments, clientCapabilities,
                optionalScreenContextId, relationshipProfile, formalMemoryAllowed, false);
    }

    public TurnRequest(String userId, String clientId, String sessionId, String parentSessionId, String message,
                       List<Map<String, Object>> attachments,
                       ClientCapabilities clientCapabilities,
                       String optionalScreenContextId,
                       Relationship relationshipProfile,
                       boolean formalMemoryAllowed,
                       boolean trainingMode) {
        this(userId, clientId, sessionId, parentSessionId, message, attachments, clientCapabilities,
                optionalScreenContextId, relationshipProfile, formalMemoryAllowed, trainingMode, "default");
    }

    public TurnRequest(String userId, String clientId, String sessionId, String parentSessionId, String message,
                       List<Map<String, Object>> attachments,
                       ClientCapabilities clientCapabilities,
                       String optionalScreenContextId,
                       Relationship relationshipProfile,
                       boolean formalMemoryAllowed,
                       boolean trainingMode,
                       String replyFormat) {
        this(userId, clientId, sessionId, parentSessionId, message, attachments, clientCapabilities,
                optionalScreenContextId, relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                RetrievalMode.compatibleDefault());
    }

    public TurnRequest(String userId, String clientId, String sessionId, String parentSessionId, String message,
                       List<Map<String, Object>> attachments,
                       ClientCapabilities clientCapabilities,
                       String optionalScreenContextId,
                       Relationship relationshipProfile,
                       boolean formalMemoryAllowed,
                       boolean trainingMode,
                       String replyFormat,
                       RetrievalMode retrievalMode) {
        this.userId = required(userId, "user_id");
        this.clientId = required(clientId, "client_id");
        if (!SetValues.CLIENT_IDS.contains(this.clientId)) {
            throw new IllegalArgumentException("unsupported client_id: " + this.clientId);
        }
        this.sessionId = required(sessionId, "session_id");
        this.parentSessionId = optional(parentSessionId);
        this.message = required(message, "message");
        this.attachments = copyAttachments(attachments);
        this.clientCapabilities = clientCapabilities == null ? new ClientCapabilities() : clientCapabilities;
        this.optionalScreenContextId = optionalScreenContextId;
        this.relationshipProfile = relationshipProfile;
        this.formalMemoryAllowed = formalMemoryAllowed;
        this.trainingMode = trainingMode;
        this.replyFormat = replyFormat(replyFormat);
        this.retrievalMode = retrievalMode == null
                ? RetrievalMode.compatibleDefault() : retrievalMode;
    }

    @JsonCreator
    public TurnRequest(
            @JsonProperty("user_id") String userId,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("parent_session_id") String parentSessionId,
            @JsonProperty("message") String message,
            @JsonProperty("attachments") List<Map<String, Object>> attachments,
            @JsonProperty("client_capabilities") ClientCapabilities clientCapabilities,
            @JsonProperty("optional_screen_context_id") String optionalScreenContextId,
            @JsonProperty("relationship_profile") Relationship relationshipProfile,
            @JsonProperty("formal_memory_allowed") Boolean formalMemoryAllowed,
            @JsonProperty("training_mode") Boolean trainingMode,
            @JsonProperty("reply_format") String replyFormat,
            @JsonProperty("retrieval_mode") RetrievalMode retrievalMode) {
        this(userId, clientId, sessionId, parentSessionId, message, attachments, clientCapabilities,
                optionalScreenContextId, relationshipProfile,
                formalMemoryAllowed != null && formalMemoryAllowed,
                trainingMode != null && trainingMode,
                replyFormat,
                retrievalMode);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String replyFormat(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim().toLowerCase();
        if (!normalized.equals("default") && !normalized.equals("zh_ja_pairs")) {
            throw new IllegalArgumentException("unsupported reply_format: " + normalized);
        }
        return normalized;
    }

    private static List<Map<String, Object>> copyAttachments(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<Map<String, Object>> copy = new ArrayList<>(source.size());
        for (Map<String, Object> row : source) {
            copy.add(row == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return Collections.unmodifiableList(copy);
    }

    @JsonProperty("user_id") public String getUserId() { return userId; }
    public String userId() { return userId; }
    @JsonProperty("client_id") public String getClientId() { return clientId; }
    public String clientId() { return clientId; }
    @JsonProperty("session_id") public String getSessionId() { return sessionId; }
    public String sessionId() { return sessionId; }
    @JsonProperty("parent_session_id") public String getParentSessionId() { return parentSessionId; }
    public String parentSessionId() { return parentSessionId; }
    @JsonProperty("message") public String getMessage() { return message; }
    public String message() { return message; }
    @JsonProperty("attachments") public List<Map<String, Object>> getAttachments() { return attachments; }
    public List<Map<String, Object>> attachments() { return attachments; }
    @JsonProperty("client_capabilities") public ClientCapabilities getClientCapabilities() { return clientCapabilities; }
    public ClientCapabilities clientCapabilities() { return clientCapabilities; }
    @JsonProperty("optional_screen_context_id") public String getOptionalScreenContextId() { return optionalScreenContextId; }
    public String optionalScreenContextId() { return optionalScreenContextId; }
    @JsonProperty("relationship_profile") public Relationship getRelationshipProfile() { return relationshipProfile; }
    public Relationship relationshipProfile() { return relationshipProfile; }
    @JsonProperty("formal_memory_allowed") public boolean isFormalMemoryAllowed() { return formalMemoryAllowed; }
    public boolean getFormalMemoryAllowed() { return formalMemoryAllowed; }
    public boolean formalMemoryAllowed() { return formalMemoryAllowed; }
    @JsonProperty("training_mode") public boolean isTrainingMode() { return trainingMode; }
    public boolean getTrainingMode() { return trainingMode; }
    public boolean trainingMode() { return trainingMode; }
    @JsonProperty("reply_format") public String getReplyFormat() { return replyFormat; }
    public String replyFormat() { return replyFormat; }
    @JsonProperty("retrieval_mode") public RetrievalMode getRetrievalMode() { return retrievalMode; }
    public RetrievalMode retrievalMode() { return retrievalMode; }

    /**
     * Rebinds the memory permission after the adapter identity has been
     * authenticated. The request body must not be the authority for this
     * field in hosted mode.
     */
    public TurnRequest withFormalMemoryAllowed(boolean allowed) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, allowed, trainingMode, replyFormat, retrievalMode);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TurnRequest that)) return false;
        return formalMemoryAllowed == that.formalMemoryAllowed && trainingMode == that.trainingMode && userId.equals(that.userId)
                && clientId.equals(that.clientId) && sessionId.equals(that.sessionId) && Objects.equals(parentSessionId, that.parentSessionId) && message.equals(that.message)
                && attachments.equals(that.attachments) && clientCapabilities.equals(that.clientCapabilities)
                && Objects.equals(optionalScreenContextId, that.optionalScreenContextId)
                && relationshipProfile == that.relationshipProfile && replyFormat.equals(that.replyFormat)
                && retrievalMode == that.retrievalMode;
    }
    @Override public int hashCode() { return Objects.hash(userId, clientId, sessionId, parentSessionId, message, attachments, clientCapabilities, optionalScreenContextId, relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat, retrievalMode); }
    @Override public String toString() { return "TurnRequest[userId=" + userId + ", clientId=" + clientId + ", sessionId=" + sessionId + ", parentSessionId=" + parentSessionId + ", message=" + message + ", attachments=" + attachments + ", clientCapabilities=" + clientCapabilities + ", optionalScreenContextId=" + optionalScreenContextId + ", relationshipProfile=" + relationshipProfile + ", formalMemoryAllowed=" + formalMemoryAllowed + ", trainingMode=" + trainingMode + ", replyFormat=" + replyFormat + ", retrievalMode=" + retrievalMode + "]"; }
}
