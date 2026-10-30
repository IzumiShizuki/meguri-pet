package com.meguri.core.capability;

import io.netty.channel.ChannelOption;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.ByteBufFlux;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/** Dedicated credential-free client. Original URI retains TLS hostname verification and SNI. */
final class PinnedWebFetchTransport implements PublicWebFetcher.Transport {
    @Override public Mono<PublicWebFetcher.Response> get(PublicWebFetchPolicy.Approved target) {
        return Mono.using(() -> new PinnedResolver(target), resolver ->
                client(resolver).get().uri(target.uri().toASCIIString())
                        .response(PinnedWebFetchTransport::receive).single(), AddressResolverGroup::close);
    }

    static Mono<PublicWebFetcher.Response> receive(HttpClientResponse response, ByteBufFlux content) {
        int status = response.status().code();
        String location = response.responseHeaders().get("Location", "");
        if (PublicWebFetcher.redirect(status))
            return Mono.just(new PublicWebFetcher.Response(status, location, "", new byte[0]));
        if (status < 200 || status >= 300)
            return Mono.error(new IllegalArgumentException("WEB_FETCH_HTTP_" + status));
        String type = response.responseHeaders().get("Content-Type", "");
        PublicWebFetcher.validateHeaders(type,
                response.responseHeaders().get("Content-Encoding", "identity"),
                response.responseHeaders().get("Content-Length", ""));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        return content.asByteArray().doOnNext(chunk -> append(bytes, chunk))
                .then(Mono.fromSupplier(() -> new PublicWebFetcher.Response(status, "", type, bytes.toByteArray())));
    }

    static void append(ByteArrayOutputStream out, byte[] chunk) {
        if (chunk.length > PublicWebFetcher.MAX_BYTES - out.size())
            throw new IllegalArgumentException("WEB_FETCH_BODY_TOO_LARGE");
        out.writeBytes(chunk);
    }

    static HttpClient client(AddressResolverGroup<InetSocketAddress> resolver) {
        return HttpClient.newConnection().resolver(resolver).noProxy()
                .protocol(HttpProtocol.HTTP11).followRedirect(false).disableRetry(true)
                .keepAlive(false).compress(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(5))
                .httpResponseDecoder(spec -> spec.maxHeaderSize(8192).maxInitialLineLength(4096).maxChunkSize(8192))
                .headers(headers -> {
                    headers.clear();
                    headers.set("Accept", "text/html,application/xhtml+xml,text/plain");
                    headers.set("Accept-Encoding", "identity");
                    headers.set("User-Agent", "Meguri-PublicDocumentReader/1");
                });
    }

    static final class PinnedResolver extends AddressResolverGroup<InetSocketAddress> {
        private final PublicWebFetchPolicy.Approved target;
        PinnedResolver(PublicWebFetchPolicy.Approved target) {
            this.target = target;
            if (target.addresses().isEmpty() || target.addresses().stream().anyMatch(a -> !PublicWebFetchPolicy.isPublic(a)))
                throw new IllegalArgumentException("WEB_FETCH_ADDRESS_REJECTED");
        }
        @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
                // Even already-resolved literals must match this hop; never pass through an arbitrary IP.
                @Override protected boolean doIsResolved(InetSocketAddress address) { return false; }
                private List<InetSocketAddress> pinned(InetSocketAddress address) throws Exception {
                    String host = target.uri().getHost();
                    int port = target.uri().getScheme().equals("https") ? 443 : 80;
                    if (!sameHost(address.getHostString(), host) || address.getPort() != port)
                        throw new IllegalArgumentException("WEB_FETCH_RESOLVER_TARGET_MISMATCH");
                    java.util.ArrayList<InetSocketAddress> values = new java.util.ArrayList<>();
                    for (InetAddress approved : target.addresses())
                        values.add(new InetSocketAddress(InetAddress.getByAddress(host, approved.getAddress()), port));
                    return List.copyOf(values);
                }
                @Override protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) throws Exception {
                    promise.setSuccess(pinned(address).getFirst());
                }
                @Override protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) throws Exception {
                    promise.setSuccess(pinned(address));
                }
            };
        }

        private static boolean sameHost(String actual, String expected) {
            if (actual.equalsIgnoreCase(expected)) return true;
            // Numeric literals can reach Netty expanded or without URI brackets. Parse bytes,
            // never resolve names here: the approved DNS snapshot remains the only resolver.
            byte[] left = io.netty.util.NetUtil.createByteArrayFromIpAddressString(actual);
            byte[] right = io.netty.util.NetUtil.createByteArrayFromIpAddressString(expected);
            return left != null && right != null && java.util.Arrays.equals(left, right);
        }
    }
}
