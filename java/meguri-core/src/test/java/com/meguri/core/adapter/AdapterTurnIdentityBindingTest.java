package com.meguri.core.adapter;

import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.adapter.domain.AdapterIdentityContext;
import com.meguri.core.adapter.domain.AdapterProtocolCapabilities;
import com.meguri.core.adapter.domain.AdapterProtocolHello;
import com.meguri.core.adapter.domain.AdapterProtocolPermissions;
import com.meguri.core.adapter.domain.AdapterTurnCreateRequest;
import com.meguri.core.adapter.domain.PlatformActorMapper;
import com.meguri.core.adapter.infrastructure.InMemoryClientBindingRepository;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.harness.retrieval.RetrievalMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterTurnIdentityBindingTest {

    @Test
    void mapsPlatformActorBeforeCorePersistenceAndRejectsActorSwap() {
        ClientHandshakeService handshake =
                new ClientHandshakeService(new InMemoryClientBindingRepository());
        AdapterIdentityContext boundIdentity = identity("actor-raw-1");
        handshake.negotiate(
                "tenant-1",
                "user-1",
                hello(boundIdentity),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll());
        var binding = handshake.binding("instance-1").orElseThrow();

        var request = turn(boundIdentity).toCoreRequest(binding);

        assertThat(request.getPlatformActorId())
                .isEqualTo(PlatformActorMapper.hash("qq", "actor-raw-1"))
                .doesNotContain("actor-raw-1");
        assertThat(request.getRequestedExecutionMode()).isEqualTo(TurnExecutionMode.AGENT);
        assertThatThrownBy(() -> turn(identity("actor-raw-2")).toCoreRequest(binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void postgresSchemaPersistsOnlyTheMappedPlatformActor() throws Exception {
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("db/adapter-runtime.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(
                    input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("platform_actor_hash char(64)")
                    .doesNotContain("platform_actor_id");
        }
    }

    private static AdapterProtocolHello hello(AdapterIdentityContext identity) {
        return new AdapterProtocolHello(
                List.of("1.0"),
                identity,
                new AdapterProtocolCapabilities(true, true, false, false, false, false),
                new AdapterProtocolPermissions(false, false, false, false, false),
                List.of(),
                "astrbot-test");
    }

    private static AdapterTurnCreateRequest turn(AdapterIdentityContext identity) {
        return new AdapterTurnCreateRequest(
                "1.0",
                identity,
                "hello",
                List.of(),
                null,
                false,
                "default",
                RetrievalMode.FAST,
                TurnExecutionMode.AGENT,
                List.of());
    }

    private static AdapterIdentityContext identity(String actorId) {
        return new AdapterIdentityContext(
                new AdapterIdentityContext.MeguriUser("user-1"),
                new AdapterIdentityContext.PlatformActor("qq", actorId),
                new AdapterIdentityContext.ClientInstance("instance-1", "astrbot"),
                new AdapterIdentityContext.Session("session-1"));
    }
}
