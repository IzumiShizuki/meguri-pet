package com.meguri.core.capability;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClientResponse;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PinnedWebFetchTransportTest {
    @ParameterizedTest
    @ValueSource(strings={"application/json","application/pdf","image/svg+xml","application/octet-stream","","text/event-stream","text/html; charset=not-a-charset"})
    void rejectsUnsupportedTypes(String type) {
        assertThatThrownBy(()->PublicWebFetcher.validateHeaders(type,"identity","12"))
                .hasMessage("WEB_FETCH_RESPONSE_REJECTED");
    }
    @ParameterizedTest
    @ValueSource(strings={"gzip","br","deflate","identity,gzip",""})
    void rejectsUnrequestedEncoding(String encoding) {
        assertThatThrownBy(()->PublicWebFetcher.validateHeaders("text/html",encoding,""))
                .hasMessage("WEB_FETCH_RESPONSE_REJECTED");
    }
    @Test void rejectsOversizedLengthAndStreamingBodyWithoutLength() {
        for(String length:List.of("2000001","-1","99999999999999999999999999","NaN"))
            assertThatThrownBy(()->PublicWebFetcher.validateHeaders("text/plain","identity",length))
                    .hasMessage("WEB_FETCH_RESPONSE_REJECTED");
        var bytes=new ByteArrayOutputStream();
        PinnedWebFetchTransport.append(bytes,new byte[1_900_000]);
        assertThatThrownBy(()->PinnedWebFetchTransport.append(bytes,new byte[100_001]))
                .hasMessage("WEB_FETCH_BODY_TOO_LARGE");
        assertThat(bytes.size()).isEqualTo(1_900_000);
    }
    @Test void productionResponseHandlerConsumesBoundedChunksAndIgnoresCookies() {
        var response=mock(HttpClientResponse.class);
        when(response.status()).thenReturn(HttpResponseStatus.OK);
        when(response.responseHeaders()).thenReturn(new DefaultHttpHeaders()
                .set("Content-Type","text/plain; charset=UTF-8").set("Set-Cookie","synthetic=value"));
        var buffer=Unpooled.copiedBuffer("public document",StandardCharsets.UTF_8);
        try {
            var result=PinnedWebFetchTransport.receive(response,ByteBufFlux.fromInbound(Flux.just(buffer))).block();
            assertThat(new String(result.body(),StandardCharsets.UTF_8)).isEqualTo("public document");
            assertThat(result.location()).isEmpty();
        } finally {ReferenceCountUtil.safeRelease(buffer);}
    }
    @Test void productionResponseHandlerRejectsUnknownLengthStreamAtByteLimit() {
        var response=mock(HttpClientResponse.class);
        when(response.status()).thenReturn(HttpResponseStatus.OK);
        when(response.responseHeaders()).thenReturn(new DefaultHttpHeaders().set("Content-Type","text/plain"));
        var first=Unpooled.wrappedBuffer(new byte[1_000_000]);
        var second=Unpooled.wrappedBuffer(new byte[1_000_001]);
        try {
            assertThatThrownBy(()->PinnedWebFetchTransport.receive(response,ByteBufFlux.fromInbound(Flux.just(first,second))).block())
                    .hasMessage("WEB_FETCH_BODY_TOO_LARGE");
        } finally {ReferenceCountUtil.safeRelease(first);ReferenceCountUtil.safeRelease(second);}
    }
    @Test void redirectsDoNotDownloadTheBodyAndErrorStatusDoesNotBecomeDocument() {
        var response=mock(HttpClientResponse.class);
        when(response.status()).thenReturn(HttpResponseStatus.FOUND);
        when(response.responseHeaders()).thenReturn(new DefaultHttpHeaders().set("Location","/next").set("Set-Cookie","x=y"));
        var subscribed=new java.util.concurrent.atomic.AtomicBoolean();
        var body=ByteBufFlux.fromInbound(Flux.never().doOnSubscribe(x->subscribed.set(true)));
        assertThat(PinnedWebFetchTransport.receive(response,body).block().location()).isEqualTo("/next");
        assertThat(subscribed).isFalse();
        when(response.status()).thenReturn(HttpResponseStatus.TOO_MANY_REQUESTS);
        assertThatThrownBy(()->PinnedWebFetchTransport.receive(response,body).block()).hasMessage("WEB_FETCH_HTTP_429");
        assertThat(subscribed).isFalse();
    }
    @Test void dedicatedClientContainsNoCredentialsAndUsesPinnedResolver() throws Exception {
        var target=new PublicWebFetchPolicy.Approved(URI.create("https://docs.example/"),List.of(InetAddress.getByName("93.184.216.34")));
        try(var resolver=new PinnedWebFetchTransport.PinnedResolver(target)){
            var client=PinnedWebFetchTransport.client(resolver);
            assertThat(client.configuration().headers().get("Accept-Encoding")).isEqualTo("identity");
            assertThat(client.configuration().headers().names()).doesNotContain("Cookie","Authorization","Proxy-Authorization","Referer");
            assertThat(client.configuration().resolver()).isSameAs(resolver);
            assertThat(client.configuration().isRetryDisabled()).isTrue();
            assertThat(client.configuration().followRedirectPredicate()).isNull();
        }
    }
}
