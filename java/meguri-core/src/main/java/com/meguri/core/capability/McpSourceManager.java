package com.meguri.core.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.TurnRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration and lifecycle boundary for remote MCP sources.
 * Secrets are referenced by environment-variable name and never persisted in source status.
 */
@Component
public final class McpSourceManager implements AutoCloseable, McpContentResolver {
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");
    private static final Set<String> TRANSPORT_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade");

    private final CapabilityRuntimeFacade runtime;
    private final McpAdapterFactory adapterFactory;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final McpSourceStore store;
    private final String configuredSources;
    private final Map<String, ManagedSource> sources = new ConcurrentHashMap<>();

    @Autowired
    public McpSourceManager(
            CapabilityRuntimeFacade runtime,
            ObjectMapper objectMapper,
            McpSourceStore store,
            @Value("${meguri.capability.mcp.sources-json:}") String configuredSources) {
        this(runtime, new HttpMcpAdapter.Factory(objectMapper), objectMapper,
                System::getenv, store, configuredSources);
    }

    public McpSourceManager(
            CapabilityRuntimeFacade runtime,
            ObjectMapper objectMapper,
            String configuredSources) {
        this(runtime, new HttpMcpAdapter.Factory(objectMapper), objectMapper,
                System::getenv, new InMemoryMcpSourceStore(), configuredSources);
    }

    McpSourceManager(
            CapabilityRuntimeFacade runtime,
            McpAdapterFactory adapterFactory,
            ObjectMapper objectMapper,
            Environment environment,
            String configuredSources) {
        this(runtime, adapterFactory, objectMapper, environment,
                new InMemoryMcpSourceStore(), configuredSources);
    }

    McpSourceManager(
            CapabilityRuntimeFacade runtime,
            McpAdapterFactory adapterFactory,
            ObjectMapper objectMapper,
            Environment environment,
            McpSourceStore store,
            String configuredSources) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.adapterFactory = Objects.requireNonNull(adapterFactory, "adapterFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.store = Objects.requireNonNull(store, "store");
        this.configuredSources = configuredSources == null ? "" : configuredSources.trim();
    }

    @PostConstruct
    void loadConfiguredSources() {
        LinkedHashMap<String, SourceConfiguration> configured =
                new LinkedHashMap<>();
        store.configurations().forEach(configuration ->
                configured.put(configuration.id(), configuration));
        try {
            if (!configuredSources.isBlank()) {
                List<SourceConfiguration> values = objectMapper.readValue(
                        configuredSources, new TypeReference<>() { });
                values.forEach(configuration ->
                        configured.put(configuration.id(), configuration));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("MCP source configuration is invalid", failure);
        }
        configured.values().forEach(configuration -> {
            try {
                register(configuration);
            } catch (RuntimeException failure) {
                sources.put(configuration.id(),
                        ManagedSource.failed(configuration.redacted(), failure));
            }
        });
    }

    public synchronized SourceStatus register(SourceConfiguration configuration) {
        SourceConfiguration safe = validate(configuration);
        SourceConfiguration previousStored = store.find(safe.id()).orElse(null);
        store.save(safe);
        try {
            McpAdapter adapter = adapterFactory.create(
                    safe, resolveAuthorization(safe));
            ConnectedSource connected = connect(safe, adapter);
            ManagedSource previous = sources.put(safe.id(),
                    ManagedSource.connected(
                            safe.redacted(), connected.synchronizer(), connected.protocolVersion()));
            retireReplacedVersions(previous, connected.synchronizer());
            if (previous != null && previous.synchronizer != null) {
                previous.synchronizer.deactivate();
            }
            return status(safe.id());
        } catch (RuntimeException failure) {
            restoreStoredConfiguration(safe.id(), previousStored);
            ManagedSource previous = sources.get(safe.id());
            if (previous == null) {
                sources.put(safe.id(), ManagedSource.failed(safe.redacted(), failure));
            } else {
                previous.fail(failure);
            }
            throw new McpSourceException("MCP source registration failed closed", failure);
        }
    }

    public synchronized SourceStatus sync(String sourceId) {
        ManagedSource source = require(sourceId);
        if (source.synchronizer == null) {
            return register(source.configuration);
        }
        try {
            source.synchronizer.refresh(source.protocolVersion);
            source.succeed();
            return status(sourceId);
        } catch (RuntimeException failure) {
            source.fail(failure);
            throw new McpSourceException("MCP source refresh failed closed", failure);
        }
    }

    /** Explicit hook for a caller-controlled, bounded polling schedule. */
    public synchronized SourceStatus poll(String sourceId) {
        ManagedSource source = require(sourceId);
        if (source.synchronizer == null) return register(source.configuration);
        try {
            source.synchronizer.pollForListChanges(source.protocolVersion);
            source.succeed();
            return status(sourceId);
        } catch (RuntimeException failure) {
            source.fail(failure);
            throw new McpSourceException("MCP source poll failed closed", failure);
        }
    }

    /** Reads one previously allowlisted prompt from one explicit source namespace. */
    public List<McpExternalContent> getPrompt(
            String sourceId,
            String promptName,
            Map<String, Object> arguments,
            Set<String> authorizedScopes) {
        requireScope(sourceId, authorizedScopes);
        ManagedSource source = requireConnected(sourceId);
        return source.synchronizer.getPrompt(
                CapabilityDescriptor.required(promptName, "promptName"),
                arguments == null ? Map.of() : Map.copyOf(arguments));
    }

    /** Reads one previously allowlisted resource from one explicit source namespace. */
    public List<McpExternalContent> readResource(
            String sourceId, String uri, Set<String> authorizedScopes) {
        requireScope(sourceId, authorizedScopes);
        ManagedSource source = requireConnected(sourceId);
        return source.synchronizer.readResource(
                CapabilityDescriptor.required(uri, "resource uri"));
    }

    private static void requireScope(String sourceId, Set<String> authorizedScopes) {
        String requiredScope = "mcp:" + safeId(sourceId);
        if (authorizedScopes == null || !authorizedScopes.contains(requiredScope)) {
            throw new SecurityException("MCP source scope is not authorized");
        }
    }

    @Override
    public List<McpExternalContent> resolve(
            TurnRequest.McpContentSelection selection,
            Set<String> authorizedScopes) {
        Objects.requireNonNull(selection, "selection");
        return switch (selection.kind()) {
            case PROMPT -> getPrompt(
                    selection.sourceId(), selection.identifier(),
                    selection.arguments(), authorizedScopes);
            case RESOURCE -> readResource(
                    selection.sourceId(), selection.identifier(), authorizedScopes);
        };
    }

    public synchronized SourceStatus remove(String sourceId) {
        ManagedSource source = require(sourceId);
        store.delete(source.configuration.id());
        if (source.synchronizer != null) {
            source.synchronizer.knownVersions().keySet().forEach(runtime::drain);
            source.synchronizer.deactivate();
        }
        sources.remove(sourceId);
        return new SourceStatus(source.configuration, SourceState.REMOVED, 0,
                Map.of(), Instant.now(), null);
    }

    public List<SourceStatus> statuses() {
        return sources.keySet().stream().sorted().map(this::status).toList();
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        sources.values().forEach(source -> {
            if (source.synchronizer != null) source.synchronizer.deactivate();
        });
        sources.clear();
    }

    public SourceStatus status(String sourceId) {
        ManagedSource source = require(sourceId);
        Map<String, String> versions = source.synchronizer == null
                ? Map.of() : source.synchronizer.knownVersions();
        return new SourceStatus(source.configuration, source.state, source.protocolVersion,
                versions, source.updatedAt, source.errorCode);
    }

    private ConnectedSource connect(SourceConfiguration configuration, McpAdapter adapter) {
        McpCapabilitySynchronizer synchronizer = new McpCapabilitySynchronizer(
                configuration.id(), adapter,
                new NoopCatalog(), new NoopRegistry(), new McpCapabilityNormalizer(),
                new McpCapabilitySynchronizer.Lifecycle() {
                    @Override
                    public void activateAll(
                            List<McpCapabilitySynchronizer.PreparedCapability> capabilities) {
                        runtime.registerBatch(capabilities.stream()
                                .map(capability ->
                                        new CapabilityRuntimeFacade.CapabilityRegistration(
                                                capability.descriptor(),
                                                capability.implementation()))
                                .toList());
                    }

                    @Override
                    public void drain(String capabilityId) {
                        runtime.drain(capabilityId);
                    }

                    @Override
                    public void retire(String capabilityId, String version) {
                        runtime.drainVersion(capabilityId, version);
                    }
                });
        McpAdapter.Negotiation negotiation = synchronizer.start(configuration.maximumProtocol());
        return new ConnectedSource(synchronizer, negotiation.protocolVersion());
    }

    private void retireReplacedVersions(
            ManagedSource previous, McpCapabilitySynchronizer replacement) {
        if (previous == null || previous.synchronizer == null) return;
        Map<String, String> next = replacement.knownVersions();
        previous.synchronizer.knownVersions().forEach((id, version) -> {
            String replacementVersion = next.get(id);
            if (replacementVersion == null) {
                runtime.drain(id);
            } else if (!replacementVersion.equals(version)) {
                runtime.drainVersion(id, version);
            }
        });
    }

    private String resolveAuthorization(SourceConfiguration configuration) {
        if (configuration.authorizationEnvironment() == null) return null;
        String value = environment.get(configuration.authorizationEnvironment());
        if (value == null || value.isBlank()) {
            throw new McpSourceException("MCP authorization environment is unavailable");
        }
        return value.trim();
    }

    private void restoreStoredConfiguration(
            String sourceId, SourceConfiguration previous) {
        if (previous == null) {
            store.delete(sourceId);
        } else {
            store.save(previous);
        }
    }

    private static SourceConfiguration validate(SourceConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        String id = safeId(configuration.id());
        URI endpoint = Objects.requireNonNull(configuration.endpoint(), "endpoint");
        String scheme = endpoint.getScheme() == null
                ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        boolean local = "localhost".equalsIgnoreCase(endpoint.getHost())
                || "127.0.0.1".equals(endpoint.getHost())
                || "::1".equals(endpoint.getHost());
        if (!"https".equals(scheme) && !(configuration.allowInsecureLocalhost() && local
                && "http".equals(scheme))) {
            throw new IllegalArgumentException("MCP endpoint must use HTTPS");
        }
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint cannot contain credentials or fragment");
        }
        if (configuration.maximumProtocol() < 1) {
            throw new IllegalArgumentException("maximumProtocol must be positive");
        }
        Map<String, String> headers = new LinkedHashMap<>(
                configuration.headers() == null ? Map.of() : configuration.headers());
        headers.keySet().forEach(name -> {
            if (SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || name.toLowerCase(Locale.ROOT).matches(".*(secret|token|password|credential).*")) {
                throw new IllegalArgumentException(
                        "sensitive MCP headers must use authorizationEnvironment");
            }
        });
        String authEnvironment = configuration.authorizationEnvironment();
        if (authEnvironment != null
                && !authEnvironment.matches("[A-Z][A-Z0-9_]{1,127}")) {
            throw new IllegalArgumentException("authorizationEnvironment is invalid");
        }
        return new SourceConfiguration(id, endpoint, configuration.maximumProtocol(),
                authEnvironment, Map.copyOf(headers), configuration.allowInsecureLocalhost());
    }

    private ManagedSource require(String sourceId) {
        ManagedSource source = sources.get(safeId(sourceId));
        if (source == null) throw new IllegalArgumentException("unknown MCP source");
        return source;
    }

    private ManagedSource requireConnected(String sourceId) {
        ManagedSource source = require(sourceId);
        if (source.state != SourceState.CONNECTED || source.synchronizer == null) {
            throw new McpSourceException("MCP source is not connected");
        }
        return source;
    }

    private static String safeId(String value) {
        String id = CapabilityDescriptor.required(value, "source id").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("MCP source id is unsafe");
        }
        return id;
    }

    public interface McpAdapterFactory {
        McpAdapter create(SourceConfiguration configuration, String authorization);
    }

    @FunctionalInterface
    interface Environment {
        String get(String name);
    }

    public record SourceConfiguration(
            String id,
            URI endpoint,
            int maximumProtocol,
            String authorizationEnvironment,
            Map<String, String> headers,
            boolean allowInsecureLocalhost) {
        SourceConfiguration redacted() {
            return new SourceConfiguration(id, endpoint, maximumProtocol,
                    authorizationEnvironment, headers, allowInsecureLocalhost);
        }
    }

    public record SourceStatus(
            SourceConfiguration configuration,
            SourceState state,
            int protocolVersion,
            Map<String, String> capabilityVersions,
            Instant updatedAt,
            String errorCode) {
        public SourceStatus {
            capabilityVersions = Map.copyOf(capabilityVersions);
        }
    }

    public enum SourceState { CONNECTED, FAILED, REMOVED }

    public static final class McpSourceException extends RuntimeException {
        public McpSourceException(String message) {
            super(message);
        }

        public McpSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ManagedSource {
        private final SourceConfiguration configuration;
        private final McpCapabilitySynchronizer synchronizer;
        private volatile SourceState state;
        private volatile int protocolVersion;
        private volatile Instant updatedAt;
        private volatile String errorCode;

        private ManagedSource(
                SourceConfiguration configuration,
                McpCapabilitySynchronizer synchronizer,
                SourceState state,
                int protocolVersion,
                String errorCode) {
            this.configuration = configuration;
            this.synchronizer = synchronizer;
            this.state = state;
            this.protocolVersion = protocolVersion;
            this.updatedAt = Instant.now();
            this.errorCode = errorCode;
        }

        static ManagedSource connected(
                SourceConfiguration configuration,
                McpCapabilitySynchronizer synchronizer,
                int protocolVersion) {
            return new ManagedSource(
                    configuration, synchronizer, SourceState.CONNECTED, protocolVersion, null);
        }

        static ManagedSource failed(SourceConfiguration configuration, Throwable failure) {
            ManagedSource source = new ManagedSource(
                    configuration, null, SourceState.FAILED, 0, null);
            source.fail(failure);
            return source;
        }

        void succeed() {
            state = SourceState.CONNECTED;
            errorCode = null;
            updatedAt = Instant.now();
        }

        void fail(Throwable failure) {
            state = SourceState.FAILED;
            errorCode = failure.getClass().getSimpleName();
            updatedAt = Instant.now();
        }
    }

    private record ConnectedSource(
            McpCapabilitySynchronizer synchronizer,
            int protocolVersion) { }

    /**
     * The synchronizer lifecycle is redirected into the shared facade; these ports
     * are intentionally unreachable and prevent a second registry from becoming authoritative.
     */
    private static final class NoopCatalog implements CapabilityCatalog {
        public void register(CapabilityDescriptor d, CapabilityImplementation i) {
            throw new UnsupportedOperationException();
        }
        public java.util.Optional<Definition> find(String id, String version) {
            return java.util.Optional.empty();
        }
        public List<Definition> definitions() { return List.of(); }
        public void remove(String id, String version) { }
    }

    private static final class NoopRegistry implements CapabilityRegistry {
        public void enable(String id, String version) { throw new UnsupportedOperationException(); }
        public void disable(String id) { throw new UnsupportedOperationException(); }
        public void activate(String id, String version) { throw new UnsupportedOperationException(); }
        public void setHealth(String id, String version, CapabilityDescriptor.Health health) {
            throw new UnsupportedOperationException();
        }
        public void drain(String id) { throw new UnsupportedOperationException(); }
        public CapabilitySnapshot snapshot() {
            return new CapabilitySnapshot("noop", Instant.EPOCH, List.of());
        }
    }
}
