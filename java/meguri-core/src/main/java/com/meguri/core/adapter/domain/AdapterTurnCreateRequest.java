package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.adapter.application.UnsupportedRequiredExtensionException;
import com.meguri.core.adapter.application.UnsupportedProtocolVersionException;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.harness.retrieval.RetrievalMode;

import java.util.List;
import java.util.Map;

/** Canonical asynchronous Turn request, converted to the existing core command. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterTurnCreateRequest(
        @JsonProperty("protocol_version") String protocolVersion,
        AdapterIdentityContext identity,
        String message,
        List<Map<String, Object>> attachments,
        @JsonProperty("relationship_profile") Relationship relationshipProfile,
        @JsonProperty("training_mode") Boolean trainingMode,
        @JsonProperty("reply_format") String replyFormat,
        @JsonProperty("retrieval_mode") RetrievalMode retrievalMode,
        @JsonProperty("execution_mode") TurnExecutionMode executionMode,
        @JsonProperty("required_extensions") List<String> requiredExtensions) {

    public AdapterTurnCreateRequest {
        ProtocolVersion.parse(protocolVersion);
        if (identity == null) throw new IllegalArgumentException("identity is required");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        requiredExtensions = requiredExtensions == null ? List.of() : List.copyOf(requiredExtensions);
    }

    public AdapterTurnCreateRequest(
            String protocolVersion,
            AdapterIdentityContext identity,
            String message,
            List<Map<String, Object>> attachments,
            Relationship relationshipProfile,
            Boolean trainingMode,
            String replyFormat,
            RetrievalMode retrievalMode,
            List<String> requiredExtensions) {
        this(protocolVersion, identity, message, attachments, relationshipProfile,
                trainingMode, replyFormat, retrievalMode, null, requiredExtensions);
    }

    public TurnRequest toCoreRequest(ClientBinding binding) {
        if (binding == null) throw new IllegalArgumentException("Client Hello binding is required");
        String platformActorHash = PlatformActorMapper.hash(
                identity.platformActor().platform(), identity.platformActor().actorId());
        if (!binding.meguriUserId().equals(identity.meguriUser().id())
                || !binding.clientId().equals(identity.clientInstance().profile())
                || !binding.clientInstanceId().equals(identity.clientInstance().id())
                || binding.platformActorHash() == null
                || !binding.platformActorHash().equals(platformActorHash)) {
            throw new IllegalArgumentException("Turn identity does not match Client Hello binding");
        }
        ProtocolVersion requestVersion = ProtocolVersion.parse(protocolVersion);
        ProtocolVersion selectedVersion = ProtocolVersion.parse(binding.selectedProtocolVersion());
        if (!requestVersion.isCompatibleWith(selectedVersion)) {
            throw new UnsupportedProtocolVersionException(
                    "Turn protocol major does not match the negotiated binding");
        }
        if (!requiredExtensions.isEmpty()) {
            throw new UnsupportedRequiredExtensionException(
                    "unsupported required extensions: " + String.join(",", requiredExtensions));
        }
        AdapterClientCapabilities advertised = binding.capabilities();
        ClientCapabilities coreCapabilities = new ClientCapabilities(
                advertised.text(), advertised.sprite(), advertised.voice(), advertised.screen());
        TurnRequest request = new TurnRequest(
                identity.meguriUser().id(),
                identity.clientInstance().profile(),
                identity.session().id(),
                null,
                message,
                attachments,
                coreCapabilities,
                null,
                relationshipProfile,
                binding.permissions().formalMemoryAllowed(),
                trainingMode != null && trainingMode,
                replyFormat,
                retrievalMode);
        return request.withRequestedExecutionMode(executionMode).withAdapterIdentity(
                        identity.platformActor().platform(),
                        platformActorHash,
                        identity.clientInstance().id())
                .withTenantId(binding.tenantId());
    }
}
