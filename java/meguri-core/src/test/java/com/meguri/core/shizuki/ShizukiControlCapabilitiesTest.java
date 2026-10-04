package com.meguri.core.shizuki;

import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityResult;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.capability.ToolProposal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ShizukiControlCapabilitiesTest {

    @Test
    void registersAllProtocolOperationsAndGatesThemByScopeAndDeepMode() {
        RecordingClient client = new RecordingClient();
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(64)) {
            ShizukiControlCapabilities capabilities = new ShizukiControlCapabilities(client);
            capabilities.registerInto(runtime);

            CapabilityRuntimeFacade.TurnCapabilities authorized = runtime.freeze(context(
                    "turn-authorized", Set.of(ShizukiControlCapabilities.REQUIRED_SCOPE),
                    CapabilityDescriptor.Mode.DEEP));
            CapabilityRuntimeFacade.TurnCapabilities withoutScope = runtime.freeze(context(
                    "turn-no-scope", Set.of(), CapabilityDescriptor.Mode.DEEP));
            CapabilityRuntimeFacade.TurnCapabilities fast = runtime.freeze(context(
                    "turn-fast", Set.of(ShizukiControlCapabilities.REQUIRED_SCOPE),
                    CapabilityDescriptor.Mode.FAST));

            assertThat(runtime.exposedDescriptors(authorized))
                    .filteredOn(descriptor -> descriptor.id().startsWith("shizuki."))
                    .hasSize(19)
                    .filteredOn(descriptor -> descriptor.kind()
                            == CapabilityDescriptor.Kind.LOCAL_CONTROL_TOOL)
                    .hasSize(18);
            assertThat(withoutScope.exposed().stream()
                    .filter(ref -> ref.id().startsWith("shizuki."))).isEmpty();
            assertThat(fast.exposed().stream()
                    .filter(ref -> ref.id().startsWith("shizuki."))).isEmpty();
        }
    }

    @Test
    void mapsExplicitCapabilityToFixedProtocolCommandWithoutSecondaryApproval() {
        RecordingClient client = new RecordingClient();
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(64)) {
            new ShizukiControlCapabilities(client).registerInto(runtime);
            CapabilityRuntimeFacade.TurnCapabilities frozen = runtime.freeze(context(
                    "turn", Set.of(ShizukiControlCapabilities.REQUIRED_SCOPE),
                    CapabilityDescriptor.Mode.DEEP));

            CapabilityResult result = runtime.execute(frozen, proposal(
                    "turn", "shizuki.open.music"));

            assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCESS);
            assertThat(result.data()).containsEntry("summary", "Shizuki 已打开音乐页。");
            assertThat(result.data().toString()).doesNotContain("secret-token");
            assertThat(client.command).hasValue("app.navigate");
            assertThat(client.payload.get()).containsEntry("destination", "music");
        }
    }

    @Test
    void stateReadReturnsModelSafeSummary() {
        RecordingClient client = new RecordingClient();
        try (CapabilityRuntimeFacade runtime = new CapabilityRuntimeFacade(64)) {
            new ShizukiControlCapabilities(client).registerInto(runtime);
            CapabilityRuntimeFacade.TurnCapabilities frozen = runtime.freeze(context(
                    "turn", Set.of(ShizukiControlCapabilities.REQUIRED_SCOPE),
                    CapabilityDescriptor.Mode.DEEP));

            CapabilityResult result = runtime.execute(frozen, proposal(
                    "turn", ShizukiControlCapabilities.STATE_READ));

            assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCESS);
            assertThat(result.data()).containsEntry("summary", "Shizuki is ready");
            assertThat(result.data().toString()).doesNotContain("secret-token");
        }
    }

    private static ExposurePlanner.ExposureContext context(
            String turnId,
            Set<String> scopes,
            CapabilityDescriptor.Mode mode) {
        return new ExposurePlanner.ExposureContext(
                turnId, "tenant", "user", "desktop_pet", scopes, mode,
                Set.of(), 1, false, CapabilityDescriptor.DataClassification.INTERNAL);
    }

    private static ToolProposal proposal(String turnId, String capabilityId) {
        return new ToolProposal(
                turnId, "trace", "tenant", "user", "desktop_pet", capabilityId,
                Map.of(), Set.of(ShizukiControlCapabilities.REQUIRED_SCOPE),
                null, null, null, false, 100);
    }

    private static final class RecordingClient implements ShizukiControlClient {
        private final AtomicReference<String> command = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> payload = new AtomicReference<>();

        @Override
        public Map<String, Object> readState() {
            return Map.of(
                    "status", "online",
                    "summary", "Shizuki is ready",
                    "token", "secret-token");
        }

        @Override
        public Map<String, Object> execute(String command, Map<String, Object> payload) {
            this.command.set(command);
            this.payload.set(payload);
            return Map.of(
                    "status", "executed",
                    "summary", "transport summary",
                    "token", "secret-token",
                    "result", Map.of("accepted", true));
        }
    }
}
