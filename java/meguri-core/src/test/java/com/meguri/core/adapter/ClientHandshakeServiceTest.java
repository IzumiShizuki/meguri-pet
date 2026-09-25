package com.meguri.core.adapter;

import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.adapter.application.UnsupportedProtocolVersionException;
import com.meguri.core.adapter.application.UnsupportedRequiredExtensionException;
import com.meguri.core.adapter.domain.AdapterIdentityContext;
import com.meguri.core.adapter.domain.AdapterProtocolCapabilities;
import com.meguri.core.adapter.domain.AdapterProtocolHello;
import com.meguri.core.adapter.domain.AdapterProtocolPermissions;
import com.meguri.core.adapter.domain.PlatformActorMapper;
import com.meguri.core.adapter.infrastructure.InMemoryClientBindingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientHandshakeServiceTest {

    @Test
    void selectsServerMinorAndIntersectsPermissions() {
        ClientHandshakeService service =
                new ClientHandshakeService(new InMemoryClientBindingRepository());
        var response = service.negotiate(
                "tenant-1",
                "user-1",
                hello("user-1", "instance-1", List.of("1.9", "1.0"), List.of()),
                new ClientHandshakeService.ServerPermissionEnvelope(
                        false, false, false));

        assertThat(response.selectedProtocolVersion()).isEqualTo("1.0");
        assertThat(response.effectiveCapabilities().voice()).isTrue();
        assertThat(response.grantedPermissions().microphone()).isTrue();
        assertThat(response.grantedPermissions().screenRead()).isFalse();
        assertThat(response.grantedPermissions().formalMemoryWrite()).isFalse();
        assertThat(service.binding("instance-1"))
                .get()
                .extracting(binding -> binding.platformActorHash())
                .isEqualTo(PlatformActorMapper.hash(
                        "meguri.website", "account-1"));
    }

    @Test
    void rejectsUnsupportedMajorAndRequiredExtension() {
        ClientHandshakeService service =
                new ClientHandshakeService(new InMemoryClientBindingRepository());
        assertThatThrownBy(() -> service.negotiate(
                "tenant-1",
                "user-1",
                hello("user-1", "instance-major", List.of("2.0"), List.of()),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll()))
                .isInstanceOf(UnsupportedProtocolVersionException.class);
        assertThatThrownBy(() -> service.negotiate(
                "tenant-1",
                "user-1",
                hello("user-1", "instance-extension", List.of("1.0"), List.of("future.v2")),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll()))
                .isInstanceOf(UnsupportedRequiredExtensionException.class);
    }

    @Test
    void refusesToRebindClientInstanceToAnotherUser() {
        ClientHandshakeService service =
                new ClientHandshakeService(new InMemoryClientBindingRepository());
        service.negotiate(
                "tenant-1",
                "user-1",
                hello("user-1", "stable-instance", List.of("1.0"), List.of()),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll());

        assertThatThrownBy(() -> service.negotiate(
                "tenant-1",
                "user-2",
                hello("user-2", "stable-instance", List.of("1.0"), List.of()),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another identity");
    }

    @Test
    void rejectsMeguriUserSpoofingAndPlatformActorRebinding() {
        ClientHandshakeService service =
                new ClientHandshakeService(new InMemoryClientBindingRepository());

        assertThatThrownBy(() -> service.negotiate(
                "tenant-1",
                "authenticated-user",
                hello("forged-user", "forged-instance",
                        List.of("1.0"), List.of()),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authenticated user");

        service.negotiate(
                "tenant-1",
                "user-1",
                hello("user-1", "stable-actor-instance",
                        List.of("1.0"), List.of(), "account-1"),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll());

        assertThatThrownBy(() -> service.negotiate(
                "tenant-1",
                "user-1",
                hello("user-1", "stable-actor-instance",
                        List.of("1.0"), List.of(), "account-2"),
                ClientHandshakeService.ServerPermissionEnvelope.denyAll()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another identity");
    }

    private static AdapterProtocolHello hello(
            String userId,
            String instanceId,
            List<String> versions,
            List<String> requiredExtensions) {
        return hello(userId, instanceId, versions, requiredExtensions, "account-1");
    }

    private static AdapterProtocolHello hello(
            String userId,
            String instanceId,
            List<String> versions,
            List<String> requiredExtensions,
            String actorId) {
        return new AdapterProtocolHello(
                versions,
                new AdapterIdentityContext(
                        new AdapterIdentityContext.MeguriUser(userId),
                        new AdapterIdentityContext.PlatformActor("meguri.website", actorId),
                        new AdapterIdentityContext.ClientInstance(instanceId, "website"),
                        new AdapterIdentityContext.Session("session-1")),
                new AdapterProtocolCapabilities(
                        true, true, true, true, true, true),
                new AdapterProtocolPermissions(
                        true, true, true, true, true),
                requiredExtensions,
                "test-client");
    }
}
