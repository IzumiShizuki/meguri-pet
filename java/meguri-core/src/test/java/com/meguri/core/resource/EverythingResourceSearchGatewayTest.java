package com.meguri.core.resource;

import com.meguri.core.resources.ResourceSearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EverythingResourceSearchGatewayTest {
    @TempDir
    Path root;

    @Test
    void stripsAtPrefixLimitsResultsAndFiltersSecretsAndOutsidePaths() throws Exception {
        Path allowed = Files.createDirectory(root.resolve("allowed"));
        Path report = Files.writeString(allowed.resolve("日报.md"), "ok");
        Path secret = Files.writeString(allowed.resolve(".env"), "not returned");
        Path outside = Files.writeString(root.resolve("outside.txt"), "not returned");
        StubClient client = new StubClient(true, List.of(hit(secret), hit(outside), hit(report)));
        EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client, List.of(allowed));

        ResourceSearchResponse response = gateway.search("@ 日报", 200).block();

        assertThat(response).isNotNull();
        assertThat(response.query()).isEqualTo("日报");
        assertThat(response.available()).isTrue();
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().path()).isEqualTo(report.toRealPath().toString());
        assertThat(response.candidates().getFirst().kind()).isEqualTo("file");
        assertThat(client.requestedMax).isEqualTo(200);
    }

    @Test
    void reportsUnavailableWithoutCallingSearch() {
        StubClient client = new StubClient(false, List.of());
        EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client, List.of(root));

        ResourceSearchResponse response = gateway.search("资料", 8).block();

        assertThat(response).isNotNull();
        assertThat(response.available()).isFalse();
        assertThat(response.candidates()).isEmpty();
        assertThat(client.called).isFalse();
    }

    private static EverythingSearchHit hit(Path path) throws Exception {
        return new EverythingSearchHit(path.toString(), Files.size(path), Instant.now());
    }

    private static final class StubClient extends EsEverythingSearchGateway {
        private final boolean available;
        private final List<EverythingSearchHit> hits;
        private boolean called;
        private int requestedMax;

        private StubClient(boolean available, List<EverythingSearchHit> hits) {
            super(true, Path.of("missing-es.exe"), Duration.ofSeconds(1), 500);
            this.available = available;
            this.hits = hits;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<EverythingSearchHit> search(String query, int maxResults) {
            called = true;
            requestedMax = maxResults;
            return hits;
        }
    }
}
