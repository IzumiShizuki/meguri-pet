package com.meguri.core.resource;

import com.meguri.core.resources.ResourceSearchResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EverythingResourceSearchGatewayTest {
    @Test
    void stripsAtPrefixLimitsResultsAndFiltersSecretsWhileKeepingSafeLocalPaths() throws Exception {
        Path safeRoot = safeLocalDirectory();
        try {
            Path allowed = Files.createDirectory(safeRoot.resolve("allowed"));
            Path report = Files.writeString(allowed.resolve("日报.md"), "ok");
            Path secret = Files.writeString(allowed.resolve(".env"), "not returned");
            Path outside = Files.writeString(safeRoot.resolve("outside.txt"), "not returned");
            StubClient client = new StubClient(true, List.of(hit(secret), hit(outside), hit(report)));
            EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client);

            ResourceSearchResponse response = gateway.search("@ 日报", 200).block();

            assertThat(response).isNotNull();
            assertThat(response.query()).isEqualTo("日报");
            assertThat(response.available()).isTrue();
            assertThat(response.candidates()).extracting(candidate -> candidate.path())
                    .containsExactly(outside.toRealPath().toString(), report.toRealPath().toString());
            assertThat(response.candidates()).allSatisfy(candidate -> assertThat(candidate.kind()).isEqualTo("file"));
            assertThat(client.requestedMax).isEqualTo(200);
        } finally {
            deleteTree(safeRoot);
        }
    }

    @Test
    void keepsASafeFileReturnedFromAnyLocalFolder() throws Exception {
        Path safeRoot = safeLocalDirectory();
        try {
            Files.createDirectory(safeRoot.resolve("workspace"));
            Path otherLocalFolder = Files.createDirectory(safeRoot.resolve("other-local-folder"));
            Path report = Files.writeString(otherLocalFolder.resolve("report.md"), "metadata only");
            StubClient client = new StubClient(true, List.of(hit(report)));
            EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client);

            ResourceSearchResponse response = gateway.search("report", 8).block();

            assertThat(response).isNotNull();
            assertThat(response.available()).isTrue();
            assertThat(response.candidates()).extracting(candidate -> candidate.path())
                    .containsExactly(report.toRealPath().toString());
        } finally {
            deleteTree(safeRoot);
        }
    }

    @Test
    void reportsUnavailableWithoutCallingSearch() {
        StubClient client = new StubClient(false, List.of());
        EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client);

        ResourceSearchResponse response = gateway.search("资料", 8).block();

        assertThat(response).isNotNull();
        assertThat(response.available()).isFalse();
        assertThat(response.candidates()).isEmpty();
        assertThat(client.called).isFalse();
    }

    @Test
    void filtersProtectedNonLocalAndStaleIndexEntriesWithoutClaimingNoMatch() throws Exception {
        Path safeRoot = safeLocalDirectory();
        try {
            Path protectedDirectory = Files.createDirectory(safeRoot.resolve("AppData"));
            Path protectedFile = Files.writeString(protectedDirectory.resolve("token.txt"), "not returned");
            Path staleFile = safeRoot.resolve("deleted-before-selection.md");
            StubClient client = new StubClient(true, List.of(
                    hit(protectedFile),
                    new EverythingSearchHit("\\\\server\\share\\report.md", 1L, Instant.now()),
                    new EverythingSearchHit(staleFile.toString(), 1L, Instant.now())
            ));
            EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client);

            ResourceSearchResponse response = gateway.search("report", 8).block();

            assertThat(response).isNotNull();
            assertThat(response.available()).isTrue();
            assertThat(response.candidates()).isEmpty();
            assertThat(response.message()).isEqualTo("Matching local resources were found but could not be safely offered.");
        } finally {
            deleteTree(safeRoot);
        }
    }

    @Test
    void distinguishesAnEmptyIndexResultFromFilteredCandidates() {
        StubClient client = new StubClient(true, List.of());
        EverythingResourceSearchGateway gateway = new EverythingResourceSearchGateway(client);

        ResourceSearchResponse response = gateway.search("does-not-exist", 8).block();

        assertThat(response).isNotNull();
        assertThat(response.available()).isTrue();
        assertThat(response.candidates()).isEmpty();
        assertThat(response.message()).isEqualTo("No local resource matched the query.");
    }

    private static EverythingSearchHit hit(Path path) throws Exception {
        return new EverythingSearchHit(path.toString(), Files.size(path), Instant.now());
    }

    private static Path safeLocalDirectory() throws Exception {
        return Files.createTempDirectory(Path.of(System.getProperty("user.home")), "meguri-everything-test-");
    }

    private static void deleteTree(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
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
