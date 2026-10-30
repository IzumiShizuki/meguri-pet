package com.meguri.core.capability;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Exact web_fetch contract used by text-only Skills; not an alias for search. */
public final class PublicWebFetchCapability implements CapabilityImplementation {
    public static final String ID = "web_fetch";
    private final PublicWebFetcher fetcher;
    public PublicWebFetchCapability(PublicWebFetcher fetcher) { this.fetcher = fetcher; }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(ID, "1", CapabilityDescriptor.Kind.READ_TOOL, "meguri-public-web",
                new CapabilityDescriptor.Schema(Map.of("url", CapabilityDescriptor.ValueType.STRING,
                        "extractMode", CapabilityDescriptor.ValueType.STRING, "maxChars", CapabilityDescriptor.ValueType.NUMBER),
                        Set.of("url"), false, Set.of()),
                new CapabilityDescriptor.Schema(Map.of("url", CapabilityDescriptor.ValueType.STRING,
                        "title", CapabilityDescriptor.ValueType.STRING, "text", CapabilityDescriptor.ValueType.STRING,
                        "links", CapabilityDescriptor.ValueType.ARRAY, "truncated", CapabilityDescriptor.ValueType.BOOLEAN,
                        "linksTruncated", CapabilityDescriptor.ValueType.BOOLEAN, "redirects", CapabilityDescriptor.ValueType.NUMBER,
                        "trust", CapabilityDescriptor.ValueType.STRING), Set.of("url", "text", "links", "trust"), false, Set.of()),
                Set.of("capability:read"), CapabilityDescriptor.SideEffect.READ, CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(13), CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(2),
                Set.of(CapabilityDescriptor.Mode.BALANCED, CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.PUBLIC, CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL,
                "public-web-fetch-v1", CapabilityDescriptor.Health.HEALTHY, false, 1,
                new CapabilityDescriptor.NetworkPolicy(true, Set.of()), Set.of(),
                CapabilityDescriptor.CostPolicy.free(), CapabilityDescriptor.CachePolicy.disabled(),
                CapabilityDescriptor.IdempotencyPolicy.none());
    }

    @Override public Map<String,Object> invoke(Map<String,Object> input, ExecutionContext context) {
        descriptor().inputSchema().validate(input, "input");
        Object rawLimit = input.getOrDefault("maxChars", 8000);
        if (!(rawLimit instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException("WEB_FETCH_ARGUMENT_INVALID");
        Object rawMode = input.getOrDefault("extractMode", "text");
        if (!(rawMode instanceof String mode) || !(input.get("url") instanceof String url))
            throw new IllegalArgumentException("WEB_FETCH_ARGUMENT_INVALID");
        return fetcher.fetch(url, mode, number.intValue()).block();
    }
}
