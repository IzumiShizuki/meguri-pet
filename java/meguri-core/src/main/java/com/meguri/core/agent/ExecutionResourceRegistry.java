package com.meguri.core.agent;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry of execution domains, capacity and health. Schedulers and worker
 * threads deliberately belong to {@link StepDispatcher}, not this registry.
 */
public final class ExecutionResourceRegistry {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration retryInterval;

    public ExecutionResourceRegistry() {
        this(Clock.systemUTC(), Duration.ofMillis(10));
    }

    public ExecutionResourceRegistry(Clock clock, Duration retryInterval) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryInterval = Objects.requireNonNull(retryInterval, "retryInterval");
        if (retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("retryInterval must be positive");
        }
    }

    public void register(ResourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (entries.putIfAbsent(descriptor.resourceId(), new Entry(descriptor)) != null) {
            throw new IllegalStateException("resource already registered: " + descriptor.resourceId());
        }
    }

    public Optional<ResourceSnapshot> resource(String resourceId) {
        Entry entry = entries.get(resourceId);
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
    }

    public Collection<ResourceSnapshot> resources() {
        return entries.values().stream().map(Entry::snapshot).toList();
    }

    public void setHealth(String resourceId, Health health) {
        requireEntry(resourceId).health = Objects.requireNonNull(health, "health");
    }

    public ResourceSnapshot selectHealthyRemoteAgent(
            String agentId, Set<String> capabilities, String resultSchemaId) {
        ResourceSnapshot selected = selectRemoteAgent(
                agentId, capabilities, resultSchemaId);
        if (selected.health() != Health.HEALTHY) {
            throw new AgentUnavailableException(
                    "no healthy compatible agent: " + agentId);
        }
        return selected;
    }

    public ResourceSnapshot selectRemoteAgent(
            String agentId, Set<String> capabilities, String resultSchemaId) {
        return entries.values().stream()
                .map(Entry::snapshot)
                .filter(resource -> resource.descriptor().domain() == ExecutionDomain.REMOTE_AGENT)
                .filter(resource -> resource.descriptor().agentId().equals(agentId))
                .filter(resource -> resource.descriptor().capabilities().containsAll(capabilities))
                .filter(resource -> resource.descriptor().resultSchemaIds().contains(resultSchemaId))
                .sorted(java.util.Comparator
                        .comparing((ResourceSnapshot value) ->
                                value.health() == Health.HEALTHY ? 0 : 1)
                        .thenComparing(value -> value.descriptor().resourceId()))
                .findFirst()
                .orElseThrow(() -> new AgentUnavailableException(
                        "no compatible agent: " + agentId));
    }

    public Mono<CapacityLease> acquire(String resourceId, AdmissionRequest request) {
        Entry entry = requireEntry(resourceId);
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> {
            if (entry.health != Health.HEALTHY) {
                if (!request.required()) {
                    return Mono.error(new AgentSkippedException(
                            "optional agent skipped: resource is not healthy"));
                }
                return Mono.error(new AgentUnavailableException("resource is not healthy: " + resourceId));
            }
            CapacityLease immediate = entry.tryAcquire(request);
            if (immediate != null) return Mono.just(immediate);
            if (!request.required()) {
                return Mono.error(new AgentSkippedException("optional agent skipped: capacity unavailable"));
            }
            if (entry.queued.incrementAndGet() > entry.descriptor.maxQueueSize()) {
                entry.queued.decrementAndGet();
                return Mono.error(new AgentUnavailableException("resource queue is full: " + resourceId));
            }
            return retry(entry, request).doFinally(ignored -> entry.queued.decrementAndGet());
        });
    }

    public CapacityLease restoreInFlight(
            String resourceId, String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank()
                || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId and userId are required");
        }
        CapacityLease restored = requireEntry(resourceId)
                .tryRestore(tenantId, userId);
        if (restored == null) {
            throw new AgentUnavailableException(
                    "persisted in-flight tasks exceed configured capacity: "
                            + resourceId);
        }
        return restored;
    }

    private Mono<CapacityLease> retry(Entry entry, AdmissionRequest request) {
        return Mono.defer(() -> {
            if (request.cancellation().isCancelled()) {
                return Mono.error(new AgentCancelledException("agent admission cancelled"));
            }
            if (!clock.instant().isBefore(request.deadline())) {
                return Mono.error(new AgentDeadlineExceededException("agent admission deadline exceeded"));
            }
            if (entry.health != Health.HEALTHY) {
                return Mono.error(new AgentUnavailableException("resource became unhealthy"));
            }
            CapacityLease lease = entry.tryAcquire(request);
            if (lease != null) return Mono.just(lease);
            Duration remaining = Duration.between(clock.instant(), request.deadline());
            Duration delay = remaining.compareTo(retryInterval) < 0 ? remaining : retryInterval;
            if (delay.isNegative() || delay.isZero()) {
                return Mono.error(new AgentDeadlineExceededException("agent admission deadline exceeded"));
            }
            return Mono.delay(delay).then(retry(entry, request));
        });
    }

    private Entry requireEntry(String resourceId) {
        Entry entry = entries.get(resourceId);
        if (entry == null) throw new AgentUnavailableException("unknown execution resource: " + resourceId);
        return entry;
    }

    public enum Health {
        HEALTHY, DEGRADED, UNHEALTHY
    }

    public record ResourceDescriptor(
            String resourceId,
            ExecutionDomain domain,
            int submitMaxConcurrency,
            int maxInFlightTasks,
            int maxQueueSize,
            int maxTenantInFlight,
            int maxUserInFlight,
            String agentId,
            Set<String> capabilities,
            Set<String> resultSchemaIds) {
        public ResourceDescriptor {
            if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId is required");
            Objects.requireNonNull(domain, "domain");
            if (submitMaxConcurrency < 1 || maxInFlightTasks < 1 || maxQueueSize < 0
                    || maxTenantInFlight < 1 || maxUserInFlight < 1) {
                throw new IllegalArgumentException("resource capacities are invalid");
            }
            agentId = agentId == null ? "" : agentId.trim();
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
            resultSchemaIds = Set.copyOf(resultSchemaIds == null ? Set.of() : resultSchemaIds);
        }
    }

    public record AdmissionRequest(
            String tenantId,
            String userId,
            Instant deadline,
            boolean required,
            CancellationToken cancellation) {
        public AdmissionRequest {
            if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("tenantId and userId are required");
            }
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    public record ResourceSnapshot(
            ResourceDescriptor descriptor,
            Health health,
            int availableSubmitPermits,
            int availableInFlightPermits,
            int queuedTasks) {
    }

    public static final class CapacityLease implements AutoCloseable {
        private final Entry owner;
        private final String tenantId;
        private final String userId;
        private final AtomicBoolean submitReleased = new AtomicBoolean();
        private final AtomicBoolean terminalReleased = new AtomicBoolean();

        private CapacityLease(Entry owner, String tenantId, String userId) {
            this.owner = owner;
            this.tenantId = tenantId;
            this.userId = userId;
        }

        public void releaseSubmit() {
            if (submitReleased.compareAndSet(false, true)) owner.submit.release();
        }

        public boolean submitPermitHeld() {
            return !submitReleased.get();
        }

        @Override
        public void close() {
            if (!terminalReleased.compareAndSet(false, true)) return;
            releaseSubmit();
            owner.inFlight.release();
            owner.decrementQuota(tenantId, userId);
        }
    }

    private static final class Entry {
        private final ResourceDescriptor descriptor;
        private final Semaphore submit;
        private final Semaphore inFlight;
        private final AtomicInteger queued = new AtomicInteger();
        private final ConcurrentHashMap<String, AtomicInteger> tenantInFlight = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> userInFlight = new ConcurrentHashMap<>();
        private volatile Health health = Health.HEALTHY;

        private Entry(ResourceDescriptor descriptor) {
            this.descriptor = descriptor;
            submit = new Semaphore(descriptor.submitMaxConcurrency(), true);
            inFlight = new Semaphore(descriptor.maxInFlightTasks(), true);
        }

        private synchronized CapacityLease tryAcquire(AdmissionRequest request) {
            if (health != Health.HEALTHY) return null;
            AtomicInteger tenant = tenantInFlight.computeIfAbsent(request.tenantId(), ignored -> new AtomicInteger());
            AtomicInteger user = userInFlight.computeIfAbsent(userKey(request), ignored -> new AtomicInteger());
            if (tenant.get() >= descriptor.maxTenantInFlight()
                    || user.get() >= descriptor.maxUserInFlight()
                    || !inFlight.tryAcquire()) {
                return null;
            }
            if (!submit.tryAcquire()) {
                inFlight.release();
                return null;
            }
            tenant.incrementAndGet();
            user.incrementAndGet();
            return new CapacityLease(this, request.tenantId(), userKey(request));
        }

        private synchronized CapacityLease tryRestore(
                String tenantId, String userId) {
            String userKey = userKey(tenantId, userId);
            AtomicInteger tenant = tenantInFlight.computeIfAbsent(
                    tenantId, ignored -> new AtomicInteger());
            AtomicInteger user = userInFlight.computeIfAbsent(
                    userKey, ignored -> new AtomicInteger());
            if (tenant.get() >= descriptor.maxTenantInFlight()
                    || user.get() >= descriptor.maxUserInFlight()
                    || !inFlight.tryAcquire()) {
                return null;
            }
            tenant.incrementAndGet();
            user.incrementAndGet();
            CapacityLease lease = new CapacityLease(this, tenantId, userKey);
            lease.submitReleased.set(true);
            return lease;
        }

        private synchronized void decrementQuota(String tenantId, String userKey) {
            decrement(tenantInFlight, tenantId);
            decrement(userInFlight, userKey);
        }

        private ResourceSnapshot snapshot() {
            return new ResourceSnapshot(descriptor, health, submit.availablePermits(),
                    inFlight.availablePermits(), queued.get());
        }

        private static String userKey(AdmissionRequest request) {
            return userKey(request.tenantId(), request.userId());
        }

        private static String userKey(String tenantId, String userId) {
            return tenantId + '\0' + userId;
        }

        private static void decrement(Map<String, AtomicInteger> values, String key) {
            AtomicInteger value = values.get(key);
            if (value != null && value.decrementAndGet() == 0) values.remove(key, value);
        }
    }
}
