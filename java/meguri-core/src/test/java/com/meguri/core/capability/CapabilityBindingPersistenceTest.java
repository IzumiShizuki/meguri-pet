package com.meguri.core.capability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityBindingPersistenceTest {
    @Test
    void disabledBindingRemainsDisabledWhenRuntimeIsReassembled() {
        InMemoryBindings bindings = new InMemoryBindings();
        try (CapabilityRuntimeFacade first = runtime(bindings)) {
            first.disable(CapabilityRuntimeFacade.DEFAULT_READ_TOOL);
            assertThat(first.availableDescriptors())
                    .noneMatch(descriptor -> descriptor.id().equals(
                            CapabilityRuntimeFacade.DEFAULT_READ_TOOL));
        }

        try (CapabilityRuntimeFacade restored = runtime(bindings)) {
            assertThat(restored.availableDescriptors())
                    .noneMatch(descriptor -> descriptor.id().equals(
                            CapabilityRuntimeFacade.DEFAULT_READ_TOOL));
            CapabilityBindingStore.Binding binding = bindings.findBinding(
                    CapabilityRuntimeFacade.DEFAULT_READ_TOOL).orElseThrow();
            assertThat(binding.enabled()).isFalse();
            assertThat(binding.draining()).isFalse();
        }
    }

    @Test
    void postgresSchemaContainsDescriptorBindingExecutionApprovalAndAuditAuthorities()
            throws Exception {
        String sql;
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("db/capability-runtime.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
        }
        assertThat(sql)
                .contains("create table if not exists capability_definition")
                .contains("create table if not exists capability_binding")
                .contains("create table if not exists capability_execution")
                .contains("create table if not exists capability_approval")
                .contains("create table if not exists capability_audit")
                .contains("ux_capability_operation_idempotency")
                .contains("request_digest")
                .contains("result_digest")
                .contains("capability_mcp_source");
    }

    private static CapabilityRuntimeFacade runtime(
            CapabilityBindingStore bindings) {
        return new CapabilityRuntimeFacade(
                new InMemoryCapabilityCatalog(),
                new DefaultCapabilityPolicy(),
                new InMemoryApprovalService(),
                new InMemoryOperationStore(),
                new DefaultResultNormalizer(),
                new InMemoryCapabilityAudit(),
                bindings,
                8);
    }

    private static final class InMemoryBindings
            implements CapabilityBindingStore {
        private final Map<String, Binding> values = new LinkedHashMap<>();

        @Override
        public synchronized Optional<Binding> findBinding(
                String capabilityId) {
            return Optional.ofNullable(values.get(capabilityId));
        }

        @Override
        public synchronized void save(Binding binding) {
            values.put(binding.capabilityId(), new Binding(
                    binding.capabilityId(),
                    binding.version(),
                    binding.enabled(),
                    binding.draining(),
                    binding.health(),
                    binding.updatedAt() == null
                            ? Instant.now() : binding.updatedAt()));
        }
    }
}
