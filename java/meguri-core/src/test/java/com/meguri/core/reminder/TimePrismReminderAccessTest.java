package com.meguri.core.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TimePrismReminderAccessTest {
    private static final String TOKEN = "synthetic-reminder-token";
    private static final String ROUTE = "/v1/reminders/timeprism";
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsExactUploadAndFeedIdentitiesRegardlessOfChatPolicy(boolean globalAuth) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TimePrismReminderStore store = new TimePrismReminderStore(mapper, directory.toString());
        WebTestClient client = client(globalAuth, 42, TOKEN, store);

        client.post().uri(ROUTE).headers(headers -> headers.addAll(identity(true)))
                .bodyValue(upload(42)).exchange().expectStatus().isOk();
        client.get().uri(ROUTE).headers(headers -> headers.addAll(identity(false)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.events.length()").isEqualTo(1)
                .jsonPath("$.events[0].shizukiUserId").isEqualTo(42)
                .jsonPath("$.events[0].title").isEqualTo("Synthetic reminder");
        assertThat(store.feed(42, 0, 50).events()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "X-Meguri-Tenant-ID", "X-Meguri-User-ID",
            "X-Meguri-Client-ID", "X-Meguri-Session-ID"})
    void rejectsMissingAndWrongIdentityFieldsInLegacyModeWithoutMutatingInbox(String field) throws Exception {
        var store = new TimePrismReminderStore(new ObjectMapper(), directory.toString());
        WebTestClient client = client(false, 42, TOKEN, store);
        for (boolean missing : new boolean[]{true, false}) {
            for (boolean upload : new boolean[]{true, false}) {
                HttpHeaders headers = identity(upload);
                if (missing) headers.remove(field);
                else headers.set(field, field.equals("Authorization") ? "Bearer wrong-token" : "wrong-identity");
                WebTestClient.ResponseSpec response = upload
                        ? client.post().uri(ROUTE).headers(value -> value.addAll(headers))
                                .bodyValue(upload(42)).exchange()
                        : client.get().uri(ROUTE).headers(value -> value.addAll(headers)).exchange();
                response.expectStatus().isEqualTo(field.equals("Authorization") ? 401 : 403);
            }
        }
        assertThat(store.feed(42, 0, 50).events()).isEmpty();
    }

    @Test
    void rejectsDifferentOwnerInUploadBody() throws Exception {
        var store = new TimePrismReminderStore(new ObjectMapper(), directory.toString());
        client(false, 42, TOKEN, store).post().uri(ROUTE)
                .headers(headers -> headers.addAll(identity(true))).bodyValue(upload(43))
                .exchange().expectStatus().isForbidden();
        assertThat(store.feed(42, 0, 50).events()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failsClosedWhenOwnerOrTokenIsNotConfigured(boolean missingOwner) {
        var store = new TimePrismReminderStore(new ObjectMapper(), directory.toString());
        WebTestClient client = client(false, missingOwner ? 0 : 42, missingOwner ? TOKEN : "", store);
        client.get().uri(ROUTE).headers(headers -> headers.addAll(identity(false)))
                .exchange().expectStatus().isEqualTo(503);
        client.post().uri(ROUTE).headers(headers -> headers.addAll(identity(true)))
                .bodyValue(upload(42)).exchange().expectStatus().isEqualTo(503);
    }

    private WebTestClient client(boolean globalAuth, long owner, String token, TimePrismReminderStore store) {
        var mapper = new ObjectMapper();
        var writer = new TimePrismTodoWriter(mapper, directory.toString(), owner,
                directory.resolve("email").toString(), directory.resolve("password").toString(),
                "http://127.0.0.1:1", 250);
        var verifier = new CoreIdentityVerifier(globalAuth, "test-tenant", "", token);
        return WebTestClient.bindToController(new TimePrismReminderController(store, writer, verifier, owner))
                .webFilter(verifier).build();
    }

    private static HttpHeaders identity(boolean upload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        headers.set("X-Meguri-Tenant-ID", "test-tenant");
        headers.set("X-Meguri-User-ID", upload ? "shizuki-42" : "reminder-poller");
        headers.set("X-Meguri-Client-ID", upload ? "desktop_pet" : "astrbot");
        headers.set("X-Meguri-Session-ID", upload ? "desktop-timeprism-reminders" : "reminder-poller");
        return headers;
    }

    private static TimePrismReminderUpload upload(long owner) {
        long now = System.currentTimeMillis();
        return new TimePrismReminderUpload("a".repeat(64), owner, 701, "Synthetic reminder",
                "2026-10-20T12:00", now - 1000, "deadline", now);
    }
}
