package com.meguri.core.input;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Inbound request for deterministic prefix processing. */
public record InputResolveRequest(@JsonProperty("message") String message) {
}
