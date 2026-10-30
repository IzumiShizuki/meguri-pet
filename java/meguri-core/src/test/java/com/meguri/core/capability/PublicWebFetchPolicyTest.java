package com.meguri.core.capability;

import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PublicWebFetchPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "169.254.170.2",
            "172.16.0.1", "192.168.1.1", "100.100.100.200", "198.18.0.79", "192.0.0.9",
            "192.0.2.1", "198.51.100.2", "203.0.113.2", "168.63.129.16", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::a00:1",
            "2002:7f00:1::", "2001::1", "2001:db8::1", "3fff::1"})
    void rejectsUnsafeAddressesWithoutConnecting(String address) throws Exception {
        var policy = new PublicWebFetchPolicy(host -> List.of(InetAddress.getByName(address)));
        assertThatThrownBy(() -> policy.resolve(URI.create("https://public.example/")))
                .hasMessage("WEB_FETCH_ADDRESS_REJECTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/a", "https://a.local/", "https://a.internal/", "https://a.localhost/",
            "file:///C:/private", "ftp://example.com/", "https://user:pass@example.com/", "https://user%40x@example.com/",
            "https://example.com:8443/", "http://example.com:443/", "https://example.com./",
            "https://[fe80::1%25eth0]/", "https://example.com/a\nHeader:secret", "https://example.com\\@localhost/",
            "https://example.com/?access_token=synthetic", "https://example.com/?%61pi_key=synthetic"})
    void rejectsUnsafeUrlsBeforeDns(String raw) {
        assertThatThrownBy(() -> PublicWebFetchPolicy.parse(raw)).hasMessage("WEB_FETCH_URL_REJECTED");
    }

    @Test void preservesQueryOctetsAndSiteConstraints() {
        assertThat(PublicWebFetchPolicy.parse("HTTPS://Example.COM:443/search?q=site%3Arfc-editor.org%20RFC+9110&x=%2520#part")
                .toString()).isEqualTo("https://example.com/search?q=site%3Arfc-editor.org%20RFC+9110&x=%2520");
        assertThat(PublicWebFetchPolicy.parse("http://example.com:80").toString()).isEqualTo("http://example.com/");
    }

    @Test void rejectsMixedEmptyAndFailedDnsAnswers() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.216.34");
        for (List<InetAddress> values : List.of(List.<InetAddress>of(),
                List.of(publicIp, InetAddress.getByName("10.0.0.1")))) {
            assertThatThrownBy(() -> new PublicWebFetchPolicy(h -> values).resolve(URI.create("https://public.example/")))
                    .hasMessage("WEB_FETCH_ADDRESS_REJECTED");
        }
        assertThatThrownBy(() -> new PublicWebFetchPolicy(h -> {throw new java.net.UnknownHostException();})
                .resolve(URI.create("https://public.example/"))).isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test void pinsSnapshotWithoutAnotherDnsLookupAndRejectsUnrelatedTargets() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        var policy = new PublicWebFetchPolicy(h -> {
            lookups.incrementAndGet();
            return List.of(InetAddress.getByName(lookups.get() == 1 ? "93.184.216.34" : "127.0.0.1"));
        });
        var approved = policy.resolve(URI.create("https://public.example/doc"));
        try (var group = new PinnedWebFetchTransport.PinnedResolver(approved)) {
            var resolver = group.getResolver(ImmediateEventExecutor.INSTANCE);
            var unresolved = InetSocketAddress.createUnresolved("public.example", 443);
            assertThat(resolver.resolve(unresolved).get().getAddress().getHostAddress()).isEqualTo("93.184.216.34");
            assertThat(resolver.resolveAll(unresolved).get()).hasSize(1);
            var preResolvedRebinding = new InetSocketAddress(InetAddress.getByAddress("public.example",
                    new byte[]{127,0,0,1}),443);
            assertThat(resolver.resolve(preResolvedRebinding).get().getAddress().getHostAddress()).isEqualTo("93.184.216.34");
            assertThat(lookups).hasValue(1);
            assertThat(resolver.resolve(InetSocketAddress.createUnresolved("other.example", 443)).isSuccess()).isFalse();
            assertThat(resolver.resolve(InetSocketAddress.createUnresolved("public.example", 80)).isSuccess()).isFalse();
            assertThat(resolver.resolve(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 443)).isSuccess()).isFalse();
        }
    }

    @Test void acceptsRealGlobalIpv6SnapshotWithoutNetwork() throws Exception {
        var target = new PublicWebFetchPolicy(h -> List.of(InetAddress.getByName("2606:4700:4700::1111")))
                .resolve(PublicWebFetchPolicy.parse("https://[2606:4700:4700::1111]/"));
        try (var group = new PinnedWebFetchTransport.PinnedResolver(target)) {
            assertThat(group.getResolver(ImmediateEventExecutor.INSTANCE)
                    .resolve(InetSocketAddress.createUnresolved(target.uri().getHost(), 443)).get().getAddress()
                    .getAddress()).isEqualTo(target.addresses().getFirst().getAddress());
            // Netty may pre-resolve a numeric literal and use its expanded, bracket-free form.
            assertThat(group.getResolver(ImmediateEventExecutor.INSTANCE)
                    .resolve(new InetSocketAddress(InetAddress.getByName("2606:4700:4700::1111"),443))
                    .get().getAddress().getAddress()).isEqualTo(target.addresses().getFirst().getAddress());
        }
    }
}
