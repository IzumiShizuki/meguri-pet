package com.meguri.core;

import com.meguri.core.runtime.InMemoryTurnJournal;
import com.meguri.core.runtime.TurnJournal;
import com.meguri.core.weather.WeatherConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MeguriCoreApplicationTest {
    @Autowired
    private WebTestClient client;
    @Autowired
    private Environment environment;
    @Autowired
    private WeatherConversationService weatherConversationService;
    @Autowired
    private TurnJournal turnJournal;

    @Test
    void springContextStartsWithOfflineMockAndReportsCanonicalBuild() {
        String body = client.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("meguri-core").contains("local-mock");
        assertThat(turnJournal).isInstanceOf(InMemoryTurnJournal.class);
    }

    @Test
    void localProfileEnablesQiantangWeatherDefaults() {
        assertThat(environment.getProperty("meguri.weather.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("meguri.weather.location-name"))
                .isEqualTo("浙江省杭州市钱塘区");
        assertThat(environment.getProperty("meguri.weather.latitude", Double.class)).isEqualTo(30.323040);
        assertThat(environment.getProperty("meguri.weather.longitude", Double.class)).isEqualTo(120.493941);
        assertThat(environment.getProperty("meguri.weather.work-start-hour", Integer.class)).isEqualTo(8);
        assertThat(environment.getProperty("meguri.weather.work-end-hour", Integer.class)).isEqualTo(22);
        assertThat(ReflectionTestUtils.getField(weatherConversationService, "weather"))
                .as("Spring must inject WeatherService instead of selecting the private disabled constructor")
                .isNotNull();
    }
}
