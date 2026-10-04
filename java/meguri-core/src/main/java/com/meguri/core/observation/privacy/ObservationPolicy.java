package com.meguri.core.observation.privacy;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Local policy evaluated before any observation provider is called. */
public record ObservationPolicy(
        boolean enabled,
        boolean privacyMode,
        Set<String> blockedApplications,
        Set<String> blockedTitlePatterns,
        boolean blockSensitiveWindows,
        Set<String> sensitiveApplicationPatterns,
        Set<String> sensitiveTitlePatterns,
        double minimumConfidence,
        Duration defaultContextTtl) {

    public ObservationPolicy {
        blockedApplications = normalize(blockedApplications);
        blockedTitlePatterns = normalize(blockedTitlePatterns);
        sensitiveApplicationPatterns = normalize(sensitiveApplicationPatterns);
        sensitiveTitlePatterns = normalize(sensitiveTitlePatterns);
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
            throw new IllegalArgumentException("minimumConfidence must be between 0 and 1");
        }
        if (defaultContextTtl == null || defaultContextTtl.isZero() || defaultContextTtl.isNegative()) {
            throw new IllegalArgumentException("defaultContextTtl must be positive");
        }
    }

    public static ObservationPolicy defaults() {
        return new ObservationPolicy(
                true,
                false,
                Set.of(),
                Set.of(),
                true,
                Set.of("password", "1password", "bitwarden", "keepass", "lastpass", "authy", "paypal", "alipay", "wechat pay"),
                Set.of("password", "payment", "checkout", "credit card", "secret"),
                0.65,
                Duration.ofSeconds(30));
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
