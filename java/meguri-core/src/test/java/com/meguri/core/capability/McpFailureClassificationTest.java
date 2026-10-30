package com.meguri.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpFailureClassificationTest {
    @Test void nonPublicEndpointRemainsNonRetryableAndNeverReachesHttp() {
        HttpClient client = mock(HttpClient.class);
        try (var adapter = adapter(client, host -> List.of(InetAddress.getByAddress(new byte[]{10, 0, 0, 1})))) {
            assertFailure(adapter, "MCP_ENDPOINT_REJECTED", false);
            verifyNoInteractions(client);
        }
    }

    @Test void dnsFailureKeepsDistinctReasonWithoutLeakingTheResolverMessage() {
        HttpClient client = mock(HttpClient.class);
        try (var adapter = adapter(client, host -> { throw new UnknownHostException("PRIVATE_DNS_DETAIL"); })) {
            assertFailure(adapter, "MCP_DNS_FAILED", true);
            verifyNoInteractions(client);
        }
    }

    @Test void validRpcErrorIsNotReportedAsMalformedResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a,b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"PRIVATE_REMOTE_DETAIL\"}}".getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        try (var adapter = adapter(client, host -> List.of(InetAddress.getByAddress(new byte[]{8,8,8,8})))) {
            assertFailure(adapter, "MCP_REMOTE_ERROR", false);
            verify(client, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        }
    }

    private static HttpMcpAdapter adapter(HttpClient client, McpEndpointPolicy.AddressResolver resolver) {
        return new HttpMcpAdapter(URI.create("https://synthetic.example/rpc"), Map.of(), null,
                new ObjectMapper(), client, new McpEndpointPolicy(resolver), false);
    }
    private static void assertFailure(HttpMcpAdapter adapter, String code, boolean retryable) {
        assertThatThrownBy(() -> adapter.invoke("read", Map.of(), new CapabilityImplementation.ExecutionContext("turn", "trace", null, 1)))
                .isInstanceOf(CapabilityFailureException.class).satisfies(error -> {
                    var known = (CapabilityFailureException)error;
                    assertThat(known.safeCode()).isEqualTo(code);
                    assertThat(known.retryable()).isEqualTo(retryable);
                    assertThat(known.getMessage()).doesNotContain("PRIVATE_");
                });
    }
}
