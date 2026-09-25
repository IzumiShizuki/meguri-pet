package com.meguri.core.resource;

import com.meguri.core.resources.ResourceSearchGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/** Offline-first wiring for the local Everything resource picker. */
@Configuration
public class EverythingSearchConfiguration {
    @Bean
    public EsEverythingSearchGateway everythingEsClient(
            @Value("${meguri.resources.everything.enabled:true}") boolean enabled,
            @Value("${meguri.resources.everything.es-path:D:/Program Files/Everything/es.exe}") String esPath,
            @Value("${meguri.resources.everything.timeout-ms:1800}") long timeoutMs,
            @Value("${meguri.resources.everything.query-result-cap:200}") int queryResultCap) {
        return new EsEverythingSearchGateway(enabled, Path.of(esPath),
                Duration.ofMillis(Math.max(250, timeoutMs)), queryResultCap);
    }

    @Bean
    public ResourceSearchGateway everythingResourceSearchGateway(
            EsEverythingSearchGateway everythingEsClient) {
        return new EverythingResourceSearchGateway(everythingEsClient);
    }
}
