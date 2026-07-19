package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private final String message;
    private final List<Map<String, Object>> attachments;
    private final ClientCapabilities clientCapabilities;
    private final String optionalScreenContextId;
    private final Relationship relationshipProfile;
    private final boolean formalMemoryAllowed;

    public TurnRequest(String userId, String clientId, String sessionId, String message) {
        this(userId, clientId, sessionId, message, List.of(), new ClientCapabilities(), null, null, true);
    }

    public TurnRequest(String userId, String clientId, String sessionId, String message,
                       List<Map<String, Object>> attachments,
                       ClientCapabilities clientCapabilities,
                       String optionalScreenContextId,
                       Relationship relationshipProfile,
                       boolean formalMemoryAllowed) {
        this.userId = required(userId, "user_id");
        this.clientId = required(clientId, "client_id");
        if (!SetValues.CLIENT_IDS.contains(this.clientId)) {
            throw new IllegalArgumentException("unsupported client_id: " + this.clientId);
        }
        this.sessionId = required(sessionId, "session_id");
        this.message = required(message, "message");
        this.attachments = copyAttachments(attachments);
        this.clientCapabilities = clientCapabilities == null ? new ClientCapabilities() : clientCapabilities;
        this.optionalScreenContextId = optionalScreenContextId;
        this.relationshipProfile = relationshipProfile;
        this.formalMemoryAllowed = formalMemoryAllowed;
    }

    @JsonCreator
    public TurnRequest(
            @JsonProperty("user_id") String userId,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("message") String message,
            @JsonProperty("attachments") List<Map<String, Object>> attachments,
            @JsonProperty("client_capabilities") ClientCapabilities clientCapabilities,
            @JsonProperty("optional_screen_context_id") String optionalScreenContextId,
            @JsonProperty("relationship_profile") Relationship relationshipProfile,
            @JsonProperty("formal_memory_allowed") Boolean formalMemoryAllowed) {
        this(userId, clientId, sessionId, message, attachments, clientCapabilities,
                optionalScreenContextId, relationshipProfile,
                formalMemoryAllowed == null || formalMemoryAllowed);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
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

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TurnRequest that)) return false;
        return formalMemoryAllowed == that.formalMemoryAllowed && userId.equals(that.userId)
                && clientId.equals(that.clientId) && sessionId.equals(that.sessionId) && message.equals(that.message)
                && attachments.equals(that.attachments) && clientCapabilities.equals(that.clientCapabilities)
                && Objects.equals(optionalScreenContextId, that.optionalScreenContextId)
                && relationshipProfile == that.relationshipProfile;
    }
    @Override public int hashCode() { return Objects.hash(userId, clientId, sessionId, message, attachments, clientCapabilities, optionalScreenContextId, relationshipProfile, formalMemoryAllowed); }
    @Override public String toString() { return "TurnRequest[userId=" + userId + ", clientId=" + clientId + ", sessionId=" + sessionId + ", message=" + message + ", attachments=" + attachments + ", clientCapabilities=" + clientCapabilities + ", optionalScreenContextId=" + optionalScreenContextId + ", relationshipProfile=" + relationshipProfile + ", formalMemoryAllowed=" + formalMemoryAllowed + "]"; }
}
