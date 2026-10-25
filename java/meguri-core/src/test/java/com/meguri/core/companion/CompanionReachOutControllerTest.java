package com.meguri.core.companion;

import com.meguri.core.llm.LlmProvider;
import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.security.CoreIdentityVerifier;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class CompanionReachOutControllerTest {
    private final LlmProvider provider = mock(LlmProvider.class);
    private final SessionContextStore contexts = new SessionContextStore();
    private final WebTestClient client = WebTestClient.bindToController(new CompanionReachOutController(
            new CompanionReachOutService(provider, contexts),
            new CoreIdentityVerifier(true, "tenant", "", "test-token")))
            .build().mutate().defaultHeader("Authorization", "Bearer test-token")
            .defaultHeader("X-Meguri-Tenant-ID", "tenant")
            .defaultHeader("X-Meguri-User-ID", "owner")
            .defaultHeader("X-Meguri-Client-ID", "desktop_pet")
            .defaultHeader("X-Meguri-Session-ID", "s1").build();

    @Test
    void validatesAndBindsBodyScopeBeforeGeneration() {
        client.post().uri("/v1/companion/reach-outs")
                .bodyValue(Map.of("user_id", "intruder", "client_id", "desktop_pet", "session_id", "s1"))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/v1/companion/reach-outs")
                .bodyValue(Map.of("user_id", "owner", "client_id", "astrbot", "session_id", "s1"))
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(provider);
    }

    @Test
    void returnsNoContentWithoutACompletedExchangeAndOffersAssistantOnlyDraftOtherwise() {
        Map<String, String> body = Map.of("user_id", "owner", "client_id", "desktop_pet", "session_id", "s1");
        client.post().uri("/v1/companion/reach-outs").bodyValue(body).exchange().expectStatus().isNoContent();
        contexts.append("owner", "desktop_pet", "s1", new SessionContextStore.Message("user", "书很好看"));
        contexts.append("owner", "desktop_pet", "s1", new SessionContextStore.Message("assistant", "下次接着聊吧"));
        when(provider.generateReachOut(anyList())).thenReturn(Mono.just("兄长，要继续聊那本书吗？"));
        client.post().uri("/v1/companion/reach-outs").bodyValue(body).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.text").isEqualTo("兄长，要继续聊那本书吗？")
                .jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("reachout:"));
        var candidate = client.post().uri("/v1/companion/reach-outs").bodyValue(body).exchange()
                .expectStatus().isOk().expectBody(CompanionReachOutService.Candidate.class).returnResult().getResponseBody();
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(2);
        for (int retry = 0; retry < 2; retry++) {
            client.post().uri("/v1/companion/reach-outs/{id}/delivered", candidate.id()).bodyValue(body)
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.accepted").isEqualTo(true);
        }
        assertThat(contexts.recent("owner", "desktop_pet", "s1")).hasSize(3).last()
                .isEqualTo(new SessionContextStore.Message("assistant", candidate.text()));
    }
}
