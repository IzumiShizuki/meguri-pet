package com.meguri.core.resource;

import java.time.Instant;

/** Raw, untrusted metadata returned by an Everything client. */
public record EverythingSearchHit(
        String path,
        Long sizeBytes,
        Instant modifiedAt
) { }
