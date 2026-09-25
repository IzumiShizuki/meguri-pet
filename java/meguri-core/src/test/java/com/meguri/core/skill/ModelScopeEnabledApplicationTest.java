package com.meguri.core.skill;

import com.meguri.core.MeguriCoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = MeguriCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meguri.skills.modelscope.enabled=true",
                "meguri.skills.modelscope.bridge-token=fixture-token"
        })
@AutoConfigureWebTestClient
class ModelScopeEnabledApplicationTest {
    @Autowired private WebTestClient client;
    @Autowired private SkillSourceRegistry sources;

    @Test
    void full_core_starts_with_the_opt_in_source_without_contacting_the_network() {
        client.get().uri("/health").exchange().expectStatus().isOk();
        assertThat(sources.ids()).containsExactly("modelscope");
    }
}
