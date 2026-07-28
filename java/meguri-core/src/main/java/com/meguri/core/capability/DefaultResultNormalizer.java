package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DefaultResultNormalizer implements ResultNormalizer {
    private static final Set<String> SECRET_KEYS = Set.of(
            "secret", "password", "passwd", "token", "access_token", "refresh_token", "api_key",
            "authorization", "cookie", "set-cookie", "credential");

    @Override
    public CapabilityResult normalize(CapabilityDescriptor descriptor, Map<String, Object> raw) {
        Map<String, Object> cleaned = cleanMap(raw, descriptor.outputSchema().sensitiveFields());
        descriptor.outputSchema().validate(cleaned, "output");
        List<String> warnings = descriptor.resultTrust() == CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL
                ? List.of("UNTRUSTED_EXTERNAL_CONTENT") : List.of();
        return new CapabilityResult(CapabilityResult.Status.SUCCESS, null, cleaned, "",
                new CapabilityResult.Source(descriptor.implementationRef(), descriptor.resultTrust()),
                warnings, false);
    }

    private static Map<String, Object> cleanMap(Map<?, ?> source, Set<String> sensitive) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        if (source == null) return cleaned;
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!sensitive.contains(name) && !isSecret(name)) cleaned.put(name, clean(value, sensitive));
        });
        return cleaned;
    }

    private static Object clean(Object value, Set<String> sensitive) {
        if (value instanceof Map<?, ?> map) return cleanMap(map, sensitive);
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(item -> values.add(clean(item, sensitive)));
            return values;
        }
        return value;
    }

    private static boolean isSecret(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return SECRET_KEYS.contains(normalized)
                || normalized.endsWith("_secret")
                || normalized.endsWith("_password")
                || normalized.endsWith("_token");
    }
}
