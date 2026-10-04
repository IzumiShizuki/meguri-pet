package com.meguri.core.observation.window;

/** Screen-space rectangle for a native window. */
public record WindowBounds(int x, int y, int width, int height) {
    public WindowBounds {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window bounds must have positive width and height");
        }
    }
}
