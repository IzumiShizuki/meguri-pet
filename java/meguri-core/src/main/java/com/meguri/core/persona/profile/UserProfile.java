package com.meguri.core.persona.profile;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Typed user-owned input to Effective Persona resolution. */
public record UserProfile(
        String userId,
        String revision,
        String displayName,
        String locale,
        Set<String> communicationPreferences) {

    public UserProfile {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        userId = userId.trim();
        revision = optional(revision, "empty");
        displayName = optional(displayName, "");
        locale = optional(locale, "");
        communicationPreferences = communicationPreferences == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(
                        communicationPreferences.stream().sorted().toList()));
    }

    public static UserProfile empty(String userId) {
        return new UserProfile(userId, "empty", "", "", Set.of());
    }

    public boolean isEmpty() {
        return displayName.isBlank() && locale.isBlank()
                && communicationPreferences.isEmpty();
    }

    private static String optional(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
