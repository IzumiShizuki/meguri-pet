package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterClientCapabilities(
        boolean streaming,
        boolean text,
        boolean voice,
        boolean sprite,
        boolean screen,
        boolean files,
        @JsonProperty("semantic_cues") Set<String> semanticCues,
        @JsonProperty("supported_locales") List<String> supportedLocales) {
    private static final Set<String> SUPPORTED_CUES =
            Set.of("expression", "voice_style", "animation");

    public AdapterClientCapabilities {
        semanticCues = immutableValues(semanticCues, SUPPORTED_CUES);
        supportedLocales = supportedLocales == null
                ? List.of()
                : supportedLocales.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .limit(16)
                        .toList();
    }

    public static AdapterClientCapabilities defaults() {
        return new AdapterClientCapabilities(
                true, true, false, false, false, false, Set.of(), List.of("zh-CN"));
    }

    public AdapterClientCapabilities serverIntersection() {
        return new AdapterClientCapabilities(
                streaming,
                text,
                voice,
                sprite,
                screen,
                files,
                semanticCues,
                supportedLocales);
    }

    private static Set<String> immutableValues(Set<String> source, Set<String> allowlist) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : source) {
            if (value != null && allowlist.contains(value.trim())) values.add(value.trim());
        }
        return Set.copyOf(values);
    }
}
