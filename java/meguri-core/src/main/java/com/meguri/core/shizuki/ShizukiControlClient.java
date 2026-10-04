package com.meguri.core.shizuki;

import java.util.Map;

/** Trusted-Core port for the bounded Shizuki desktop control protocol. */
public interface ShizukiControlClient {
    Map<String, Object> readState();

    Map<String, Object> execute(String command, Map<String, Object> payload);
}
