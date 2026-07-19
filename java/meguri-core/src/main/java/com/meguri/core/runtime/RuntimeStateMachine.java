package com.meguri.core.runtime;

import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the deterministic runtime state used by the text model and renderer.
 *
 * <p>The state rules intentionally mirror {@code services/meguri_core/runtime.py}:
 * night hours use outfit 04/sleep, daytime weekends use outfit 02/private, normal
 * work hours use outfit 01/work, and evening uses outfit 03/private. Overrides are
 * scoped by user (or user:client) and expire atomically when observed.</p>
 */
public final class RuntimeStateMachine {
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    public static final Set<String> ALLOWED_EXPRESSION_TAGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    "affectionate", "angry", "confused", "embarrassed", "excited", "happy",
                    "neutral", "sad", "sleepy", "surprised", "teasing", "worried")));
    public static final List<String> TAGS = List.copyOf(ALLOWED_EXPRESSION_TAGS);
    private static final Set<String> ALLOWED_OUTFITS = Set.of("01", "02", "03", "04", "05", "06");

    private final Map<String, OverrideEntry> overrides = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ZoneId zone;

    public RuntimeStateMachine() {
        this(Clock.systemUTC(), DEFAULT_ZONE);
    }

    public RuntimeStateMachine(Clock clock) {
        this(clock, DEFAULT_ZONE);
    }

    public RuntimeStateMachine(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public Map<String, OverrideEntry> overrides() {
        return Collections.unmodifiableMap(overrides);
    }

    public List<String> tags() {
        return List.copyOf(ALLOWED_EXPRESSION_TAGS);
    }

    public void setOverride(String scope, RuntimeOverride override) {
        String normalized = requireScope(scope);
        Objects.requireNonNull(override, "override");
        validateOverride(override);
        overrides.put(normalized, new OverrideEntry(override, expiryOf(override), normalized));
    }

    public static boolean isAllowedOutfit(String outfitCode) {
        return outfitCode != null && ALLOWED_OUTFITS.contains(outfitCode);
    }

    public void clearOverride(String scope) {
        if (scope != null) {
            overrides.remove(scope.trim());
        }
    }

    public void clear() {
        overrides.clear();
    }

    public RuntimeState stateFor(TurnRequest request) {
        Objects.requireNonNull(request, "request");
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        boolean holiday = now.getDayOfWeek() == DayOfWeek.SATURDAY
                || now.getDayOfWeek() == DayOfWeek.SUNDAY;
        double hour = now.getHour() + now.getMinute() / 60.0d;

        String outfit;
        String mode;
        if (hour >= 22.0d || hour < 8.0d) {
            outfit = "04";
            mode = "sleep";
        } else if (hour < 18.0d) {
            outfit = holiday ? "02" : "01";
            mode = holiday ? "private" : "work";
        } else {
            outfit = "03";
            mode = "private";
        }
        String relationship = (mode.equals("private") || mode.equals("sleep")) ? "lover" : "sibling";

        String userId = stringValue(request, "getUserId", "userId");
        String clientId = stringValue(request, "getClientId", "clientId");
        OverrideEntry entry = overrides.get(userId);
        if (entry == null) {
            entry = overrides.get(userId + ":" + clientId);
        }
        if (entry != null) {
            Instant expiry = entry.expiresAt();
            if (expiry != null && expiry.isBefore(now.toInstant())) {
                // Remove only the entry observed by this thread; a newer override wins.
                overrides.remove(entry.scope(), entry);
            } else {
                RuntimeOverride override = entry.override();
                String overrideOutfit = stringValue(override, "getOutfitCode", "outfitCode");
                String overrideMode = stringValue(override, "getMode", "mode");
                String overrideRelationship = stringValue(override, "getRelationshipProfile", "relationshipProfile");
                if (overrideOutfit != null && !overrideOutfit.isBlank()) {
                    outfit = overrideOutfit;
                }
                if (overrideMode != null && !overrideMode.isBlank()) {
                    mode = overrideMode;
                }
                if (overrideRelationship != null && !overrideRelationship.isBlank()) {
                    relationship = overrideRelationship;
                }
            }
        }

        String requestedRelationship = stringValue(request, "getRelationshipProfile", "relationshipProfile");
        if (requestedRelationship != null && !requestedRelationship.isBlank()) {
            relationship = requestedRelationship;
        }

        boolean desktopClient = "desktop_pet".equals(clientId) || "airi".equals(clientId);
        boolean voice = capability(request, "voice") && desktopClient;
        boolean screen = capability(request, "screenContext", "screen_context") && desktopClient;
        List<ExpressionTag> tags = ALLOWED_EXPRESSION_TAGS.stream()
                .map(ExpressionTag::fromValue)
                .toList();
        return new RuntimeState(
                clientId,
                Mode.fromValue(mode),
                Relationship.fromValue(relationship),
                outfit,
                now.toOffsetDateTime().toString(),
                holiday,
                voice,
                screen,
                tags);
    }

    public RuntimeState getStateFor(TurnRequest request) {
        return stateFor(request);
    }

    private static String requireScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            throw new IllegalArgumentException("override scope must not be blank");
        }
        return scope.trim();
    }

    private static void validateOverride(RuntimeOverride override) {
        String outfit = stringValue(override, "getOutfitCode", "outfitCode");
        if (outfit != null && !ALLOWED_OUTFITS.contains(outfit)) {
            throw new IllegalArgumentException("outfit_code must be one of 01-06; 07 and 08 are disabled");
        }
        Object expiry = value(override, "getExpiresAt", "expiresAt");
        if (expiry instanceof java.time.LocalDateTime) {
            throw new IllegalArgumentException("expires_at must include a timezone offset");
        }
    }

    private static Instant expiryOf(RuntimeOverride override) {
        Object value = value(override, "getExpiresAt", "expiresAt");
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.time.OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof ZonedDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof CharSequence text) {
            try {
                return Instant.parse(text.toString());
            } catch (RuntimeException ignored) {
                try {
                    return java.time.OffsetDateTime.parse(text).toInstant();
                } catch (RuntimeException ignoredAgain) {
                    throw new IllegalArgumentException("expires_at must include a timezone offset");
                }
            }
        }
        return null;
    }

    private static boolean capability(TurnRequest request, String... names) {
        Object capabilities = value(request, "getClientCapabilities", "clientCapabilities");
        if (capabilities == null) {
            return false;
        }
        for (String name : names) {
            Object value = value(capabilities, "get" + capitalize(name), name);
            if (value instanceof Boolean bool && bool) {
                return true;
            }
        }
        return false;
    }

    /** Reflection keeps this class compatible with either JavaBean or record-style DTOs. */
    private static Object value(Object object, String getter, String accessor) {
        if (object == null) {
            return null;
        }
        try {
            return object.getClass().getMethod(getter).invoke(object);
        } catch (ReflectiveOperationException ignored) {
            try {
                return object.getClass().getMethod(accessor).invoke(object);
            } catch (ReflectiveOperationException ignoredAgain) {
                return null;
            }
        }
    }

    private static String stringValue(Object object, String getter, String accessor) {
        Object value = value(object, getter, accessor);
        return value == null ? null : String.valueOf(value);
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    public record OverrideEntry(RuntimeOverride override, Instant expiresAt, String scope) {
        public OverrideEntry(RuntimeOverride override, Instant expiresAt) {
            this(override, expiresAt, "");
        }
    }
}
