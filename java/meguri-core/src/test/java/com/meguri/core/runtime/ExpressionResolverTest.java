package com.meguri.core.runtime;

import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.ResolvedExpression;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.VoiceStyle;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionResolverTest {
    private static final String BUILD_ID = "meguri_v2_02c3db0c507d7c2d";

    @Test
    void reviewedRuntimeMapResolvesEveryExpressionAndOutfitToAnExistingPng() {
        Path repository = Path.of(System.getProperty("user.dir")).resolve("../..").normalize().toAbsolutePath();
        ExpressionResolver resolver = new ExpressionResolver(
                repository.resolve("datasets/meguri"),
                BUILD_ID,
                repository.resolve("configs/meguri_sprite_runtime_map.json"));

        for (int outfit = 1; outfit <= 8; outfit++) {
            String outfitCode = "%02d".formatted(outfit);
            RuntimeState state = new RuntimeState("airi", Mode.PRIVATE, Relationship.SIBLING,
                    outfitCode, "12:00", false, true, false, Arrays.asList(ExpressionTag.values()));
            for (ExpressionTag tag : ExpressionTag.values()) {
                for (Intensity intensity : Intensity.values()) {
                    LlmResponse response = new LlmResponse("ok", tag, intensity, VoiceStyle.NEUTRAL, List.of());
                    ResolvedExpression resolved = resolver.resolve(response, state);
                    assertEquals(tag, resolved.expressionTag());
                    assertEquals(intensity, resolved.expressionIntensity());
                    assertTrue(Files.isRegularFile(repository.resolve("data/meguri/assets/sprites/meguri")
                                    .resolve(resolved.spriteFile())),
                            () -> "missing PNG for " + outfitCode + "/" + tag + "/" + intensity
                                    + ": " + resolved.spriteFile());
                }
            }
        }
    }
}
