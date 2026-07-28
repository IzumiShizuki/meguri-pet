package com.meguri.core.capability;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpEndpointPolicyTest {
    @Test
    void acceptsOnlyPublicAddressesForRemoteHttpsEndpoint() throws Exception {
        McpEndpointPolicy publicOnly = new McpEndpointPolicy(
                host -> List.of(InetAddress.getByAddress(
                        host, new byte[] {8, 8, 8, 8})));

        assertThatCode(() -> publicOnly.validate(
                URI.create("https://mcp.example/rpc"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPrivateLinkLocalCarrierNatAndMixedDnsAnswers() throws Exception {
        for (byte[] address : List.of(
                new byte[] {10, 0, 0, 1},
                new byte[] {(byte) 169, (byte) 254, 1, 1},
                new byte[] {100, 64, 0, 1},
                new byte[] {(byte) 198, 18, 0, 1})) {
            McpEndpointPolicy policy = new McpEndpointPolicy(
                    host -> List.of(InetAddress.getByAddress(host, address)));
            assertThatThrownBy(() -> policy.validate(
                    URI.create("https://mcp.example/rpc"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-public");
        }

        McpEndpointPolicy mixed = new McpEndpointPolicy(host -> List.of(
                InetAddress.getByAddress(host, new byte[] {8, 8, 8, 8}),
                InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})));
        assertThatThrownBy(() -> mixed.validate(
                URI.create("https://mcp.example/rpc"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void insecureHttpExceptionIsLimitedToResolvedLocalhostLoopback() throws Exception {
        McpEndpointPolicy loopback = new McpEndpointPolicy(
                host -> List.of(InetAddress.getByAddress(
                        host, new byte[] {127, 0, 0, 1})));

        assertThatCode(() -> loopback.validate(
                URI.create("http://localhost:8080/rpc"), true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> loopback.validate(
                URI.create("http://localhost:8080/rpc"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> loopback.validate(
                URI.create("https://127.0.0.1/rpc"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }
}
