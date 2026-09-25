package com.meguri.core.adapter.application;

import com.meguri.core.adapter.domain.AdapterClientCapabilities;
import com.meguri.core.adapter.domain.AdapterProtocolCapabilities;
import com.meguri.core.adapter.domain.AdapterProtocolHello;
import com.meguri.core.adapter.domain.AdapterProtocolHelloResponse;
import com.meguri.core.adapter.domain.AdapterProtocolPermissions;
import com.meguri.core.adapter.domain.ClientBinding;
import com.meguri.core.adapter.domain.ClientHello;
import com.meguri.core.adapter.domain.ClientHelloResponse;
import com.meguri.core.adapter.domain.ClientPermissions;
import com.meguri.core.adapter.domain.ProtocolVersion;
import com.meguri.core.adapter.domain.PlatformActorMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Performs protocol negotiation and persists the server-authorized binding. */
public final class ClientHandshakeService {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of();

    private final ClientBindingRepository repository;
    private final ProtocolVersion serverVersion;

    public ClientHandshakeService(ClientBindingRepository repository) {
        this(repository, ProtocolVersion.CURRENT);
    }

    public ClientHandshakeService(
            ClientBindingRepository repository,
            ProtocolVersion serverVersion) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    }

    public ClientHelloResponse negotiate(
            String tenantId,
            String authenticatedUserId,
            ClientHello hello,
            ServerPermissionEnvelope serverPermissions) {
        Objects.requireNonNull(hello, "hello");
        ProtocolVersion requested = ProtocolVersion.parse(hello.protocolVersion());
        if (requested.major() != serverVersion.major()) {
            throw new UnsupportedProtocolVersionException(
                    "unsupported protocol major " + requested.major()
                            + "; server supports " + serverVersion.major());
        }
        if (!hello.capabilities().text()) {
            throw new IllegalArgumentException("text capability is required by Adapter Protocol v1");
        }
        String userId = required(authenticatedUserId, "authenticatedUserId");
        AdapterClientCapabilities selectedCapabilities =
                hello.capabilities().serverIntersection();
        ServerPermissionEnvelope allowed = serverPermissions == null
                ? ServerPermissionEnvelope.denyAll() : serverPermissions;
        ClientPermissions selectedPermissions = hello.permissions().intersect(
                allowed.formalMemory(),
                allowed.screenContext() && selectedCapabilities.screen(),
                allowed.localResourceMetadata());
        List<String> degradations = degradations(
                hello.capabilities(), selectedCapabilities, hello.permissions(), selectedPermissions);
        ProtocolVersion selectedVersion = new ProtocolVersion(
                serverVersion.major(), Math.min(requested.minor(), serverVersion.minor()));
        String revision = revision(selectedVersion, selectedCapabilities, selectedPermissions);
        ClientBinding binding = repository.save(new ClientBinding(
                hello.client().clientInstanceId(),
                required(tenantId, "tenantId"),
                userId,
                hello.client().clientId(),
                null,
                hello.client().clientVersion(),
                selectedVersion.toString(),
                revision,
                selectedCapabilities,
                selectedPermissions,
                Instant.now()));
        return new ClientHelloResponse(
                binding.selectedProtocolVersion(),
                binding.serverCapabilitiesRevision(),
                binding.clientInstanceId(),
                binding.capabilities(),
                binding.permissions(),
                degradations);
    }

    public AdapterProtocolHelloResponse negotiate(
            String tenantId,
            String authenticatedUserId,
            AdapterProtocolHello hello,
            ServerPermissionEnvelope serverPermissions) {
        Objects.requireNonNull(hello, "hello");
        ProtocolVersion selectedVersion = selectVersion(hello.protocolVersions());
        List<String> unsupportedExtensions = hello.requiredExtensions().stream()
                .filter(extension -> !SUPPORTED_EXTENSIONS.contains(extension))
                .toList();
        if (!unsupportedExtensions.isEmpty()) {
            throw new UnsupportedRequiredExtensionException(
                    "unsupported required extensions: " + String.join(",", unsupportedExtensions));
        }
        if (!hello.capabilities().text()) {
            throw new IllegalArgumentException("text capability is required by Adapter Protocol v1");
        }

        String userId = required(authenticatedUserId, "authenticatedUserId");
        if (!userId.equals(hello.identity().meguriUser().id())) {
            throw new IllegalArgumentException(
                    "meguri_user.id does not match the authenticated user");
        }
        AdapterProtocolCapabilities serverCapabilities =
                AdapterProtocolCapabilities.serverDefaults();
        AdapterProtocolCapabilities effectiveCapabilities =
                hello.capabilities().intersect(serverCapabilities);
        ServerPermissionEnvelope allowed = serverPermissions == null
                ? ServerPermissionEnvelope.denyAll() : serverPermissions;
        AdapterProtocolPermissions grantedPermissions = hello.permissions().intersect(
                effectiveCapabilities, allowed.screenContext(), allowed.formalMemory());
        String revision = revision(
                serverVersion, serverCapabilities.toBinding(),
                new ClientPermissions(false, false, false));

        ClientBinding binding = repository.save(new ClientBinding(
                hello.identity().clientInstance().id(),
                required(tenantId, "tenantId"),
                userId,
                hello.identity().clientInstance().profile(),
                PlatformActorMapper.hash(
                        hello.identity().platformActor().platform(),
                        hello.identity().platformActor().actorId()),
                hello.clientVersion(),
                selectedVersion.toString(),
                revision,
                effectiveCapabilities.toBinding(),
                grantedPermissions.toBinding(),
                Instant.now()));
        return new AdapterProtocolHelloResponse(
                binding.selectedProtocolVersion(),
                binding.serverCapabilitiesRevision(),
                serverCapabilities,
                effectiveCapabilities,
                grantedPermissions,
                SUPPORTED_EXTENSIONS.stream().sorted().toList());
    }

    public Optional<ClientBinding> binding(String clientInstanceId) {
        return repository.find(required(clientInstanceId, "clientInstanceId"));
    }

    public ServerDescription describe() {
        AdapterClientCapabilities capabilities = new AdapterClientCapabilities(
                true, true, true, true, true, true,
                java.util.Set.of("expression", "voice_style", "animation"),
                List.of("zh-CN", "ja-JP"));
        return new ServerDescription(
                serverVersion.toString(),
                revision(serverVersion, capabilities, new ClientPermissions(false, false, false)),
                capabilities);
    }

    public CanonicalServerDescription describeCanonical() {
        AdapterProtocolCapabilities capabilities =
                AdapterProtocolCapabilities.serverDefaults();
        return new CanonicalServerDescription(
                serverVersion.toString(),
                revision(serverVersion, capabilities.toBinding(),
                        new ClientPermissions(false, false, false)),
                capabilities,
                SUPPORTED_EXTENSIONS.stream().sorted().toList());
    }

    private ProtocolVersion selectVersion(List<String> advertised) {
        List<ProtocolVersion> compatible = advertised.stream()
                .map(ProtocolVersion::parse)
                .filter(version -> version.major() == serverVersion.major())
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
        if (compatible.isEmpty()) {
            throw new UnsupportedProtocolVersionException(
                    "unsupported protocol major; server supports " + serverVersion.major());
        }
        ProtocolVersion preferred = compatible.getFirst();
        return new ProtocolVersion(
                serverVersion.major(), Math.min(preferred.minor(), serverVersion.minor()));
    }

    private static List<String> degradations(
            AdapterClientCapabilities requestedCapabilities,
            AdapterClientCapabilities selectedCapabilities,
            ClientPermissions requestedPermissions,
            ClientPermissions selectedPermissions) {
        List<String> reasons = new ArrayList<>();
        if (requestedCapabilities.screen() && !selectedCapabilities.screen()) {
            reasons.add("screen_capability_unavailable");
        }
        if (requestedPermissions.formalMemoryAllowed() && !selectedPermissions.formalMemoryAllowed()) {
            reasons.add("formal_memory_not_authorized");
        }
        if (requestedPermissions.screenContextAllowed() && !selectedPermissions.screenContextAllowed()) {
            reasons.add("screen_context_not_authorized");
        }
        if (requestedPermissions.localResourceMetadataAllowed()
                && !selectedPermissions.localResourceMetadataAllowed()) {
            reasons.add("local_resource_metadata_not_authorized");
        }
        return List.copyOf(reasons);
    }

    private static String revision(
            ProtocolVersion version,
            AdapterClientCapabilities capabilities,
            ClientPermissions permissions) {
        try {
            String canonical = version + "\n" + capabilities + "\n" + permissions;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record ServerPermissionEnvelope(
            boolean formalMemory,
            boolean screenContext,
            boolean localResourceMetadata) {
        public static ServerPermissionEnvelope denyAll() {
            return new ServerPermissionEnvelope(false, false, false);
        }
    }

    public record ServerDescription(
            String protocolVersion,
            String serverCapabilitiesRevision,
            AdapterClientCapabilities capabilities) { }

    public record CanonicalServerDescription(
            String protocolVersion,
            String serverCapabilitiesRevision,
            AdapterProtocolCapabilities serverCapabilities,
            List<String> supportedExtensions) { }
}
