package com.meguri.core.resource;

import com.meguri.core.resources.ResourceSearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in local E2E against the signed voidtools ES executable and running Everything IPC window. */
@EnabledIfEnvironmentVariable(named = "MEGURI_RUN_EVERYTHING_E2E", matches = "(?i)true")
class EverythingResourceSearchLiveTest {
    @Test
    void returnsOnlySafeMetadataBelowAllowedRoots() {
        EsEverythingSearchGateway client = new EsEverythingSearchGateway(
                true, Path.of("D:/Program Files/Everything/es.exe"), Duration.ofMillis(1800), 200);
        EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(
                client, List.of(Path.of("D:/program")));

        ResourceSearchResponse response = gateway.search("README", 3).block();

        assertThat(response).isNotNull();
        assertThat(response.available()).isTrue();
        assertThat(response.candidates()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(response.candidates()).allSatisfy(candidate -> {
            assertThat(Path.of(candidate.path())).startsWith(Path.of("D:/program"));
            assertThat(candidate.kind()).isIn("file", "directory");
            assertThat(candidate.path().toLowerCase(java.util.Locale.ROOT)).doesNotContain("node_modules");
        });
    }
}
