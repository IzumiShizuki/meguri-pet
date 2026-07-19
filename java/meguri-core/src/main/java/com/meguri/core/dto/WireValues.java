package com.meguri.core.dto;

/** Internal helper shared by strict wire enums. */
final class WireValues {
    private WireValues() {}

    static <E extends Enum<E>> E enumFromValue(Class<E> type, String value) {
        if (value == null) {
            throw new IllegalArgumentException("enum value must not be null");
        }
        for (E constant : type.getEnumConstants()) {
            try {
                if (String.valueOf(type.getMethod("value").invoke(constant)).equals(value)) {
                    return constant;
                }
            } catch (ReflectiveOperationException ignored) {
                // All contract enums expose value(); this branch is only a defensive fallback.
                if (constant.name().equals(value)) {
                    return constant;
                }
            }
        }
        throw new IllegalArgumentException("unsupported " + type.getSimpleName() + ": " + value);
    }
}
