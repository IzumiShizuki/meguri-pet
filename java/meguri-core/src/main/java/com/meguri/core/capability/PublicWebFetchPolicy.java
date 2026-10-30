package com.meguri.core.capability;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Public document policy. No localhost override, proxy exception or credentials. */
final class PublicWebFetchPolicy {
    private final McpEndpointPolicy.AddressResolver resolver;

    PublicWebFetchPolicy() { this(host -> List.of(InetAddress.getAllByName(host))); }
    PublicWebFetchPolicy(McpEndpointPolicy.AddressResolver resolver) { this.resolver = resolver; }

    static URI parse(String raw) {
        try {
            if (raw == null || raw.length() > 4096 || raw.isBlank()
                    || raw.chars().anyMatch(c -> c <= 32 || c == 127 || c == '\\'))
                throw new IllegalArgumentException();
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || host.isBlank()
                    || uri.getRawUserInfo() != null || uri.isOpaque()
                    || host.endsWith(".") || host.contains("%")
                    || host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")
                    || uri.getPort() != -1 && uri.getPort() != (scheme.equals("https") ? 443 : 80))
                throw new IllegalArgumentException();
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    String key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8)
                            .toLowerCase(Locale.ROOT).replace('-', '_');
                    if (Set.of("token", "access_token", "refresh_token", "api_key", "apikey",
                            "password", "passwd", "authorization", "signature", "x_amz_signature",
                            "x_amz_credential", "x_goog_signature", "x_goog_credential").contains(key))
                        throw new IllegalArgumentException();
                }
            }
            // Preserve path/query octets: do not double-encode, drop site constraints or reorder.
            String path = uri.getRawPath();
            return URI.create(scheme + "://" + host + (path == null || path.isEmpty() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("WEB_FETCH_URL_REJECTED");
        }
    }

    Approved resolve(URI uri) throws Exception {
        List<InetAddress> answers = resolver.resolve(uri.getHost());
        if (answers == null || answers.isEmpty() || answers.size() > 64
                || answers.stream().anyMatch(address -> address == null || !isPublic(address)))
            throw new IllegalArgumentException("WEB_FETCH_ADDRESS_REJECTED");
        return new Approved(uri, List.copyOf(answers));
    }

    static boolean isPublic(InetAddress address) {
        if (!McpEndpointPolicy.isPublic(address)) return false;
        byte[] b = address.getAddress();
        int a = Byte.toUnsignedInt(b[0]), c = Byte.toUnsignedInt(b[1]);
        if (b.length == 4) {
            int d = Byte.toUnsignedInt(b[2]), e = Byte.toUnsignedInt(b[3]);
            return !(a == 192 && (c == 0 && d == 2 || c == 88 && d == 99)
                    || a == 198 && c == 51 && d == 100
                    || a == 203 && c == 0 && d == 113
                    || a == 168 && c == 63 && d == 129 && e == 16);
        }
        // Global unicast only; reject embedded-address transition/reserved/documentation ranges.
        return b.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && c == 0x01 && Byte.toUnsignedInt(b[2]) < 2)
                && !(a == 0x20 && c == 0x01 && b[2] == 0x0d && Byte.toUnsignedInt(b[3]) == 0xb8)
                && !(a == 0x20 && c == 0x02)
                && !(a == 0x3f && c == 0xff && (Byte.toUnsignedInt(b[2]) & 0xf0) == 0);
    }

    record Approved(URI uri, List<InetAddress> addresses) { }
}
