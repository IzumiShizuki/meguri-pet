package com.meguri.core.capability;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Fail-closed endpoint policy for MCP HTTP transports.
 * DNS is revalidated before every exchange to reduce rebinding exposure.
 */
final class McpEndpointPolicy {
    private final AddressResolver resolver;

    McpEndpointPolicy() {
        this(host -> List.of(InetAddress.getAllByName(host)));
    }

    McpEndpointPolicy(AddressResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    void validate(URI endpoint, boolean allowInsecureLocalhost) {
        Objects.requireNonNull(endpoint, "endpoint");
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint host is required");
        }
        String scheme = endpoint.getScheme() == null
                ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        boolean localhostName = "localhost".equalsIgnoreCase(host);
        if (!"https".equals(scheme)
                && !(allowInsecureLocalhost && localhostName && "http".equals(scheme))) {
            throw new IllegalArgumentException("MCP endpoint must use HTTPS");
        }
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "MCP endpoint cannot contain credentials or fragment");
        }

        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException(
                    "MCP endpoint DNS resolution failed closed", failure);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP endpoint DNS resolution returned no address");
        }
        for (InetAddress address : addresses) {
            if (isPublic(address)) continue;
            if (allowInsecureLocalhost && localhostName && address.isLoopbackAddress()) {
                continue;
            }
            throw new IllegalArgumentException(
                    "MCP endpoint resolved to a non-public address");
        }
    }

    private static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return !(first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first == 192 && second == 0
                    || first == 198 && (second == 18 || second == 19)
                    || first >= 224);
        }
        int first = Byte.toUnsignedInt(bytes[0]);
        return (first & 0xfe) != 0xfc;
    }

    @FunctionalInterface
    interface AddressResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
