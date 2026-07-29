package com.meguri.core.capability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts a side-effect-free prompt skill result into a bounded Context input. */
public final class PromptSkillContextContract {
    private PromptSkillContextContract() {
    }

    public static ContextInput from(
            CapabilityDescriptor descriptor,
            CapabilityResult result,
            String sourceId,
            int maximumTokens) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(result, "result");
        if (descriptor.kind() != CapabilityDescriptor.Kind.PROMPT_SKILL) {
            throw new IllegalArgumentException("capability is not a prompt skill");
        }
        if (descriptor.sideEffect() != CapabilityDescriptor.SideEffect.NONE) {
            throw new SecurityException("prompt skill must be side-effect free");
        }
        if (result.status() != CapabilityResult.Status.SUCCESS) {
            throw new PromptSkillFailure(result.errorCode());
        }
        Object raw = result.data().get("content");
        if (!(raw instanceof String content) || content.isBlank()) {
            throw new CapabilityDescriptor.SchemaViolation("prompt skill output requires non-blank content");
        }
        int tokenEstimate = tokenEstimate(content);
        if (maximumTokens < 1 || tokenEstimate > maximumTokens) {
            throw new TokenBudgetExceeded(tokenEstimate, maximumTokens);
        }
        Trust trust = descriptor.resultTrust() == CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL
                ? Trust.TRUSTED_PROMPT_SKILL : Trust.UNTRUSTED_EXTERNAL;
        Provenance provenance = new Provenance(
                descriptor.id(), descriptor.version(), result.source().provider(),
                required(sourceId, "sourceId"), Instant.now());
        return new ContextInput(
                content, trust, provenance, tokenEstimate,
                descriptor.dataClassification(), List.copyOf(result.warnings()),
                Map.of("capability_kind", descriptor.kind().name()));
    }

    static int tokenEstimate(String content) {
        return Math.max(1, (content.codePointCount(0, content.length()) + 2) / 3);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public enum Trust {
        TRUSTED_PROMPT_SKILL,
        UNTRUSTED_EXTERNAL
    }

    public record Provenance(
            String capabilityId,
            String capabilityVersion,
            String provider,
            String sourceId,
            Instant producedAt) {
        public Provenance {
            capabilityId = required(capabilityId, "capabilityId");
            capabilityVersion = required(capabilityVersion, "capabilityVersion");
            provider = required(provider, "provider");
            sourceId = required(sourceId, "sourceId");
            Objects.requireNonNull(producedAt, "producedAt");
        }
    }

    public record ContextInput(
            String content,
            Trust trust,
            Provenance provenance,
            int tokenEstimate,
            CapabilityDescriptor.DataClassification dataClassification,
            List<String> warnings,
            Map<String, String> attributes) {
        public ContextInput {
            content = required(content, "content");
            Objects.requireNonNull(trust, "trust");
            Objects.requireNonNull(provenance, "provenance");
            if (tokenEstimate < 1) throw new IllegalArgumentException("tokenEstimate must be positive");
            Objects.requireNonNull(dataClassification, "dataClassification");
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public static final class PromptSkillFailure extends IllegalStateException {
        PromptSkillFailure(String code) {
            super(code == null ? "PROMPT_SKILL_FAILED" : code);
        }
    }

    public static final class TokenBudgetExceeded extends IllegalArgumentException {
        private final int actual;
        private final int maximum;

        TokenBudgetExceeded(int actual, int maximum) {
            super("prompt skill token estimate " + actual + " exceeds budget " + maximum);
            this.actual = actual;
            this.maximum = maximum;
        }

        public int actual() { return actual; }
        public int maximum() { return maximum; }
    }
}
