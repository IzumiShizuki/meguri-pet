package com.meguri.core.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.adapter.application.UnsupportedProtocolVersionException;
import com.meguri.core.adapter.domain.AdapterProtocolHello;
import com.meguri.core.adapter.infrastructure.InMemoryClientBindingRepository;
import com.meguri.core.runtime.TurnEventTypes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterProtocolFixtureContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void everySharedProfileBindsThroughTheJavaHandshake() throws Exception {
        JsonNode profiles = mapper.readTree(Files.readString(
                contracts().resolve("fixtures/profiles.json")));
        ClientHandshakeService handshake = new ClientHandshakeService(
                new InMemoryClientBindingRepository());

        for (var fields = profiles.fields(); fields.hasNext();) {
            var profile = fields.next();
            AdapterProtocolHello hello = mapper.treeToValue(
                    profile.getValue(), AdapterProtocolHello.class);
            String userId = hello.identity().meguriUser().id();

            var response = handshake.negotiate(
                    "tenant",
                    userId,
                    hello,
                    new ClientHandshakeService.ServerPermissionEnvelope(
                            true, true, true));

            assertThat(response.selectedProtocolVersion()).isEqualTo("1.0");
            assertThat(response.effectiveCapabilities().text()).isTrue();
            assertThat(handshake.binding(
                    hello.identity().clientInstance().id())).isPresent();
        }
    }

    @Test
    void sharedReplayAndVersionFixturesMatchJavaAuthority() throws Exception {
        JsonNode flows = mapper.readTree(Files.readString(
                contracts().resolve("fixtures/flows.json")));
        for (JsonNode event : flows.path("normal")) {
            assertThat(TurnEventTypes.replayPolicy(
                    event.path("type").asText(), Map.of()).name())
                    .isEqualTo(event.path("replay_policy").asText());
        }
        JsonNode once = flows.path("once_replay");
        assertThat(TurnEventTypes.replayPolicy(
                once.path("type").asText(), Map.of()).name())
                .isEqualTo(once.path("replay_policy").asText());

        ObjectNode profile = (ObjectNode) mapper.readTree(Files.readString(
                contracts().resolve("fixtures/profiles.json"))).path("airi");
        ClientHandshakeService handshake = new ClientHandshakeService(
                new InMemoryClientBindingRepository());
        ArrayNode major = mapper.createArrayNode().add(
                flows.path("major_rejected").asText());
        profile.set("protocol_versions", major);
        AdapterProtocolHello unsupported = mapper.treeToValue(
                profile, AdapterProtocolHello.class);
        assertThatThrownBy(() -> handshake.negotiate(
                "tenant",
                unsupported.identity().meguriUser().id(),
                unsupported,
                ClientHandshakeService.ServerPermissionEnvelope.denyAll()))
                .isInstanceOf(UnsupportedProtocolVersionException.class);

        profile.set("protocol_versions", mapper.createArrayNode().add(
                flows.path("minor_accepted").asText()));
        AdapterProtocolHello compatible = mapper.treeToValue(
                profile, AdapterProtocolHello.class);
        assertThat(handshake.negotiate(
                "tenant",
                compatible.identity().meguriUser().id(),
                compatible,
                ClientHandshakeService.ServerPermissionEnvelope.denyAll())
                .selectedProtocolVersion()).isEqualTo("1.0");
    }

    private static Path contracts() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && cursor != null;
                depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(
                    "contracts/adapter-protocol/v1");
            if (Files.isRegularFile(
                    candidate.resolve("adapter-protocol.schema.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "shared adapter protocol contracts are unavailable");
    }
}
