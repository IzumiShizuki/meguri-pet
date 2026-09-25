package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.harness.retrieval.RetrievalMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final TurnExecutionMode requestedExecutionMode;
    private final String platformId;
    private final String platformActorId;
    private final String clientInstanceId;
    private final String tenantId;
    private final Set<String> authorizedCapabilityScopes;
    private final List<McpContentSelection> mcpContentSelections;
    private final AgentProposal agentProposal;

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
        this(userId, clientId, sessionId, parentSessionId, message, attachments,
                clientCapabilities, optionalScreenContextId, relationshipProfile,
                formalMemoryAllowed, trainingMode, replyFormat, retrievalMode,
                null, null, null, null, "meguri-local", Set.of(), List.of(), null);
    }

    private TurnRequest(String userId, String clientId, String sessionId, String parentSessionId, String message,
                        List<Map<String, Object>> attachments,
                        ClientCapabilities clientCapabilities,
                        String optionalScreenContextId,
                        Relationship relationshipProfile,
                        boolean formalMemoryAllowed,
                        boolean trainingMode,
                        String replyFormat,
                        RetrievalMode retrievalMode,
                        TurnExecutionMode requestedExecutionMode,
                        String platformId,
                        String platformActorId,
                        String clientInstanceId,
                        String tenantId,
                        Set<String> authorizedCapabilityScopes,
                        List<McpContentSelection> mcpContentSelections,
                        AgentProposal agentProposal) {
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
        this.requestedExecutionMode = requestedExecutionMode;
        boolean hasAdapterIdentity = platformId != null
                || platformActorId != null || clientInstanceId != null;
        if (hasAdapterIdentity
                && (platformId == null || platformActorId == null || clientInstanceId == null)) {
            throw new IllegalArgumentException("adapter identity fields must be provided together");
        }
        this.platformId = optional(platformId);
        this.platformActorId = optional(platformActorId);
        this.clientInstanceId = optional(clientInstanceId);
        this.tenantId = required(tenantId, "tenant_id");
        this.authorizedCapabilityScopes = Set.copyOf(
                authorizedCapabilityScopes == null ? Set.of() : authorizedCapabilityScopes);
        this.mcpContentSelections = mcpContentSelections == null
                ? List.of() : List.copyOf(mcpContentSelections);
        this.agentProposal = agentProposal;
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
            @JsonProperty("retrieval_mode") RetrievalMode retrievalMode,
            @JsonProperty("execution_mode") TurnExecutionMode requestedExecutionMode,
            @JsonProperty("mcp_content") List<McpContentSelection> mcpContentSelections,
            @JsonProperty("agent_proposal") AgentProposal ignoredClientAgentProposal) {
        this(userId, clientId, sessionId, parentSessionId, message, attachments,
                clientCapabilities, optionalScreenContextId, relationshipProfile,
                formalMemoryAllowed != null && formalMemoryAllowed,
                trainingMode != null && trainingMode, replyFormat,
                retrievalMode == null ? RetrievalMode.compatibleDefault() : retrievalMode,
                requestedExecutionMode, null, null, null, "meguri-local", Set.of(),
                mcpContentSelections, null);
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
    @JsonProperty("execution_mode") public TurnExecutionMode getRequestedExecutionMode() {
        return requestedExecutionMode;
    }
    public TurnExecutionMode requestedExecutionMode() { return requestedExecutionMode; }
    @JsonProperty("platform_id") public String getPlatformId() { return platformId; }
    public String platformId() { return platformId; }
    @JsonProperty("platform_actor_id") public String getPlatformActorId() { return platformActorId; }
    public String platformActorId() { return platformActorId; }
    @JsonProperty("client_instance_id") public String getClientInstanceId() { return clientInstanceId; }
    public String clientInstanceId() { return clientInstanceId; }
    @JsonProperty("tenant_id") public String getTenantId() { return tenantId; }
    public String tenantId() { return tenantId; }
    @JsonIgnore public Set<String> getAuthorizedCapabilityScopes() {
        return authorizedCapabilityScopes;
    }
    public Set<String> authorizedCapabilityScopes() { return authorizedCapabilityScopes; }
    @JsonProperty("mcp_content") public List<McpContentSelection> getMcpContentSelections() {
        return mcpContentSelections;
    }
    public List<McpContentSelection> mcpContentSelections() { return mcpContentSelections; }
    @JsonIgnore public AgentProposal getAgentProposal() { return agentProposal; }
    public AgentProposal agentProposal() { return agentProposal; }

    /**
     * Rebinds the memory permission after the adapter identity has been
     * authenticated. The request body must not be the authority for this
     * field in hosted mode.
     */
    public TurnRequest withFormalMemoryAllowed(boolean allowed) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, allowed, trainingMode, replyFormat, retrievalMode,
                requestedExecutionMode, platformId, platformActorId, clientInstanceId, tenantId,
                authorizedCapabilityScopes, mcpContentSelections, agentProposal);
    }

    public TurnRequest withAdapterIdentity(
            String platformId, String platformActorId, String clientInstanceId) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                retrievalMode, requestedExecutionMode,
                required(platformId, "platform_id"),
                required(platformActorId, "platform_actor_id"),
                required(clientInstanceId, "client_instance_id"),
                tenantId, authorizedCapabilityScopes, mcpContentSelections, agentProposal);
    }

    public TurnRequest withTenantId(String tenantId) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                retrievalMode, requestedExecutionMode, platformId, platformActorId, clientInstanceId,
                required(tenantId, "tenant_id"), authorizedCapabilityScopes,
                mcpContentSelections, agentProposal);
    }

    /** Applies server-authorized scopes after adapter authentication. */
    public TurnRequest withAuthorizedCapabilityScopes(Set<String> scopes) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                retrievalMode, requestedExecutionMode, platformId, platformActorId, clientInstanceId,
                tenantId, Objects.requireNonNull(scopes, "scopes"),
                mcpContentSelections, agentProposal);
    }

    public TurnRequest withMcpContentSelections(
            List<McpContentSelection> selections) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                retrievalMode, requestedExecutionMode, platformId, platformActorId, clientInstanceId,
                tenantId, authorizedCapabilityScopes,
                Objects.requireNonNull(selections, "selections"), agentProposal);
    }

    /** Internal planner seam; JSON clients cannot populate this proposal. */
    public TurnRequest withAgentProposal(AgentProposal proposal) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                retrievalMode, requestedExecutionMode, platformId, platformActorId, clientInstanceId,
                tenantId, authorizedCapabilityScopes, mcpContentSelections,
                Objects.requireNonNull(proposal, "proposal"));
    }

    /** Optional client preference; the server resolver remains authoritative. */
    public TurnRequest withRequestedExecutionMode(TurnExecutionMode mode) {
        return new TurnRequest(userId, clientId, sessionId, parentSessionId, message,
                attachments, clientCapabilities, optionalScreenContextId,
                relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat,
                retrievalMode, mode, platformId, platformActorId, clientInstanceId,
                tenantId, authorizedCapabilityScopes, mcpContentSelections, agentProposal);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TurnRequest that)) return false;
        return formalMemoryAllowed == that.formalMemoryAllowed && trainingMode == that.trainingMode && userId.equals(that.userId)
                && clientId.equals(that.clientId) && sessionId.equals(that.sessionId) && Objects.equals(parentSessionId, that.parentSessionId) && message.equals(that.message)
                && attachments.equals(that.attachments) && clientCapabilities.equals(that.clientCapabilities)
                && Objects.equals(optionalScreenContextId, that.optionalScreenContextId)
                && relationshipProfile == that.relationshipProfile && replyFormat.equals(that.replyFormat)
                && retrievalMode == that.retrievalMode
                && requestedExecutionMode == that.requestedExecutionMode
                && Objects.equals(platformId, that.platformId)
                && Objects.equals(platformActorId, that.platformActorId)
                && Objects.equals(clientInstanceId, that.clientInstanceId)
                && tenantId.equals(that.tenantId)
                && authorizedCapabilityScopes.equals(that.authorizedCapabilityScopes)
                && mcpContentSelections.equals(that.mcpContentSelections)
                && Objects.equals(agentProposal, that.agentProposal);
    }
    @Override public int hashCode() { return Objects.hash(userId, clientId, sessionId, parentSessionId, message, attachments, clientCapabilities, optionalScreenContextId, relationshipProfile, formalMemoryAllowed, trainingMode, replyFormat, retrievalMode, requestedExecutionMode, platformId, platformActorId, clientInstanceId, tenantId, authorizedCapabilityScopes, mcpContentSelections, agentProposal); }
    @Override public String toString() { return "TurnRequest[userId=" + userId + ", clientId=" + clientId + ", sessionId=" + sessionId + ", parentSessionId=" + parentSessionId + ", message=" + message + ", attachments=" + attachments + ", clientCapabilities=" + clientCapabilities + ", optionalScreenContextId=" + optionalScreenContextId + ", relationshipProfile=" + relationshipProfile + ", formalMemoryAllowed=" + formalMemoryAllowed + ", trainingMode=" + trainingMode + ", replyFormat=" + replyFormat + ", retrievalMode=" + retrievalMode + ", requestedExecutionMode=" + requestedExecutionMode + ", platformId=" + platformId + ", platformActorId=" + platformActorId + ", clientInstanceId=" + clientInstanceId + ", tenantId=" + tenantId + ", authorizedCapabilityScopes=" + authorizedCapabilityScopes + ", mcpContentSelections=" + mcpContentSelections.size() + ", agentProposal=" + agentProposal + "]"; }

    public record McpContentSelection(
            @JsonProperty("kind") Kind kind,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("identifier") String identifier,
            @JsonProperty("arguments") Map<String, Object> arguments,
            @JsonProperty("required") boolean required) {
        public McpContentSelection {
            kind = Objects.requireNonNull(kind, "mcp_content.kind");
            sourceId = TurnRequest.required(sourceId, "mcp_content.source_id");
            identifier = TurnRequest.required(identifier, "mcp_content.identifier");
            arguments = arguments == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }

        public enum Kind { PROMPT, RESOURCE }
    }

    public record AgentProposal(
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("task_brief") String taskBrief,
            @JsonProperty("required") boolean required,
            @JsonProperty("mode") String mode,
            @JsonProperty("idempotency_suffix") String idempotencySuffix) {
        public AgentProposal {
            agentId = TurnRequest.required(agentId, "agent_proposal.agent_id");
            taskBrief = TurnRequest.required(taskBrief, "agent_proposal.task_brief");
            mode = mode == null || mode.isBlank() ? "AWAIT" : mode.trim().toUpperCase();
            if (!Set.of("AWAIT", "DURABLE_ASYNC").contains(mode)) {
                throw new IllegalArgumentException("unsupported agent_proposal.mode: " + mode);
            }
            idempotencySuffix = idempotencySuffix == null || idempotencySuffix.isBlank()
                    ? "explicit" : idempotencySuffix.trim();
        }
    }
}
