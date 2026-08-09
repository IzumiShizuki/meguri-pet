package com.meguri.core.runtime;

import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
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
 * late-night hours (02:00-08:00) use outfit 04/sleep, daytime weekends use outfit 02/private, normal
 * work hours use outfit 01/work, and evening uses outfit 03/private. Temporal mode
 * never changes the user-level relationship. Presentation overrides may be scoped
 * by user or user:client, while relationship overrides are accepted only at the
 * shared user scope and expire atomically when observed.</p>
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
    private final Map<String, StabilizedTemporalState> temporalStates = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ZoneId zone;
    private final Duration debounce;
    private final Duration cooldown;

    public RuntimeStateMachine() {
        this(Clock.systemUTC(), DEFAULT_ZONE);
    }

    public RuntimeStateMachine(Clock clock) {
        this(clock, DEFAULT_ZONE);
    }

    public RuntimeStateMachine(Clock clock, ZoneId zone) {
        this(clock, zone, Duration.ofSeconds(30), Duration.ofMinutes(2));
    }

    public RuntimeStateMachine(Clock clock, ZoneId zone, Duration debounce, Duration cooldown) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.debounce = positive(debounce, "debounce");
        this.cooldown = positive(cooldown, "cooldown");
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
        temporalStates.clear();
    }

    public RuntimeState stateFor(TurnRequest request) {
        Objects.requireNonNull(request, "request");
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        boolean holiday = now.getDayOfWeek() == DayOfWeek.SATURDAY
                || now.getDayOfWeek() == DayOfWeek.SUNDAY;
        double hour = now.getHour() + now.getMinute() / 60.0d;

        String candidateOutfit;
        String candidateMode;
        if (hour >= 2.0d && hour < 8.0d) {
            candidateOutfit = "04";
            candidateMode = "sleep";
        } else if (hour >= 8.0d && hour < 18.0d) {
            candidateOutfit = holiday ? "02" : "01";
            candidateMode = holiday ? "private" : "work";
        } else {
            candidateOutfit = "03";
            candidateMode = "private";
        }
        String userId = stringValue(request, "getUserId", "userId");
        String clientId = stringValue(request, "getClientId", "clientId");
        TemporalState temporal = stabilize(
                userId + ":" + clientId,
                new TemporalState(candidateOutfit, candidateMode),
                now.toInstant());
        String outfit = temporal.outfit();
        String mode = temporal.mode();
        String relationship = Relationship.SIBLING.value();

        OverrideEntry userEntry = activeOverride(userId, now.toInstant());
        OverrideEntry clientEntry = activeOverride(userId + ":" + clientId, now.toInstant());
        RuntimeOverride userOverride = userEntry == null ? null : userEntry.override();
        RuntimeOverride clientOverride = clientEntry == null ? null : clientEntry.override();

        String overrideOutfit = firstNonBlank(
                stringValue(userOverride, "getOutfitCode", "outfitCode"),
                stringValue(clientOverride, "getOutfitCode", "outfitCode"));
        String overrideMode = firstNonBlank(
                stringValue(userOverride, "getMode", "mode"),
                stringValue(clientOverride, "getMode", "mode"));
        if (overrideOutfit != null) {
            outfit = overrideOutfit;
        }
        if (overrideMode != null) {
            mode = overrideMode;
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

    private OverrideEntry activeOverride(String scope, Instant now) {
        OverrideEntry entry = overrides.get(scope);
        if (entry == null) return null;
        Instant expiry = entry.expiresAt();
        if (expiry != null && !expiry.isAfter(now)) {
            // Remove only the entry observed by this thread; a newer override wins.
            overrides.remove(entry.scope(), entry);
            return null;
        }
        return entry;
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

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    private TemporalState stabilize(String scope, TemporalState candidate, Instant now) {
        StabilizedTemporalState state = temporalStates.computeIfAbsent(
                scope, ignored -> new StabilizedTemporalState(candidate, now));
        synchronized (state) {
            if (state.applied.equals(candidate)) {
                state.pending = candidate;
                state.pendingSince = now;
                return state.applied;
            }
            if (!state.pending.equals(candidate)) {
                state.pending = candidate;
                state.pendingSince = now;
                return state.applied;
            }
            if (Duration.between(state.changedAt, now).compareTo(cooldown) < 0
                    || Duration.between(state.pendingSince, now).compareTo(debounce) < 0) {
                return state.applied;
            }
            state.applied = candidate;
            state.changedAt = now;
            return state.applied;
        }
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
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

    private record TemporalState(String outfit, String mode) { }

    private static final class StabilizedTemporalState {
        private TemporalState applied;
        private TemporalState pending;
        private Instant pendingSince;
        private Instant changedAt;

        private StabilizedTemporalState(TemporalState initial, Instant now) {
            this.applied = initial;
            this.pending = initial;
            this.pendingSince = now;
            this.changedAt = now;
        }
    }
}
