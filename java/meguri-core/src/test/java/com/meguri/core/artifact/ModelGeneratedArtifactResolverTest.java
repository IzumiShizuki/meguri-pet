package com.meguri.core.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGeneratedArtifactResolverTest {
    @TempDir
    Path root;

    @Test
    void resolvesOnlyAnExistingPassiveGeneratedMarker() throws Exception {
        Path output = Files.createDirectories(root.resolve("output"));
        Path report = Files.writeString(output.resolve("summary.md"), "ready");
        ModelGeneratedArtifactResolver resolver = new ModelGeneratedArtifactResolver(root);

        var artifacts = resolver.resolve("完成：[[meguri-artifact:output/summary.md]]");

        assertEquals(1, artifacts.size());
        assertEquals("summary.md", artifacts.getFirst().label());
        assertEquals("/output/summary.md", artifacts.getFirst().href());
        assertEquals(report.toRealPath().toString(), artifacts.getFirst().localPath());
    }

    @Test
    void ignoresMissingEscapedAndExecutableArtifacts() throws Exception {
        Files.createDirectories(root.resolve("output"));
        Files.createDirectories(root.resolve("reports"));
        Files.writeString(root.resolve("output/unsafe.cmd"), "not executable by this feature");
        ModelGeneratedArtifactResolver resolver = new ModelGeneratedArtifactResolver(root);

        var artifacts = resolver.resolve("[[meguri-artifact:output/missing.pdf]] "
                + "[[meguri-artifact:output/unsafe.cmd]] "
                + "[[meguri-artifact:../outside.md]]");

        assertTrue(artifacts.isEmpty());
    }
}
