package com.meguri.core.shizuki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityRuntimeFacade;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/** Opt-in Spring wiring for the bounded local Shizuki control surface. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "meguri.shizuki", name = "enabled", havingValue = "true")
public class ShizukiControlConfiguration {
    private static final long MAX_TIMEOUT_MILLIS = 30_000;

    @Bean
    @ConditionalOnMissingBean(ShizukiControlClient.class)
    public ShizukiControlClient meguriShizukiControlClient(
            ObjectMapper mapper,
            @Value("${meguri.shizuki.manifest-path:}") String manifestPath,
            @Value("${meguri.shizuki.connect-timeout-ms:750}") long connectTimeoutMillis,
            @Value("${meguri.shizuki.request-timeout-ms:2000}") long requestTimeoutMillis) {
        return new LoopbackShizukiControlClient(
                resolveManifestPath(manifestPath), mapper,
                boundedTimeout("connect-timeout-ms", connectTimeoutMillis),
                boundedTimeout("request-timeout-ms", requestTimeoutMillis));
    }

    @Bean
    public ShizukiControlCapabilities meguriShizukiControlCapabilities(
            ShizukiControlClient client,
            CapabilityRuntimeFacade runtime) {
        ShizukiControlCapabilities capabilities = new ShizukiControlCapabilities(client);
        capabilities.registerInto(runtime);
        return capabilities;
    }

    static Path resolveManifestPath(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath.trim()).toAbsolutePath().normalize();
        }
        String appData = System.getenv("APPDATA");
        Path roaming = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming")
                : Path.of(appData);
        return roaming.resolve("Shizuki").resolve("control-api.json")
                .toAbsolutePath().normalize();
    }

    private static Duration boundedTimeout(String property, long millis) {
        if (millis < 1 || millis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "meguri.shizuki." + property + " must be between 1 and "
                            + MAX_TIMEOUT_MILLIS + " milliseconds");
        }
        return Duration.ofMillis(millis);
    }
}
