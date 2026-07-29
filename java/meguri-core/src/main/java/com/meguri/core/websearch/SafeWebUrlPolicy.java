package com.meguri.core.websearch;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Canonicalizes public HTTPS URLs and rejects local/private destinations before extraction. */
public final class SafeWebUrlPolicy {
    private final Set<String> allowlist;

    public SafeWebUrlPolicy(Set<String> allowlist) {
        this.allowlist = allowlist == null ? Set.of() : allowlist.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String canonicalize(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) return "";
            String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
            if (unsafeHost(host) || !allowed(host)) return "";
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank()
                    ? "/" : uri.getRawPath();
            return new URI("https", null, host, normalizePort(uri.getPort()), path,
                    stripTracking(uri.getRawQuery()), null).toASCIIString();
        } catch (Exception error) {
            return "";
        }
    }

    private boolean allowed(String host) {
        return allowlist.isEmpty() || allowlist.stream()
                .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private static boolean unsafeHost(String host) {
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true;
        if (host.equals("0.0.0.0") || host.equals("::1") || host.startsWith("127.")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true;
        if (host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")) return true;
        return host.contains(":") || host.matches("0*\\d+(\\.0*\\d+){3}");
    }

    private static int normalizePort(int port) {
        return port == 443 ? -1 : port;
    }

    private static String stripTracking(String query) {
        if (query == null || query.isBlank()) return null;
        String value = java.util.Arrays.stream(query.split("&"))
                .filter(part -> !part.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*"))
                .sorted().collect(java.util.stream.Collectors.joining("&"));
        return value.isBlank() ? null : value;
    }
}
