package com.meguri.core.web;

import com.meguri.core.adapter.application.AdapterProtocolException;
import com.meguri.core.adapter.domain.AdapterErrorResponse;
import com.meguri.core.adapter.domain.AdapterProtocolError;
import com.meguri.core.dto.EventEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice(assignableTypes = RuntimeWebController.class)
public final class AdapterProtocolExceptionHandler {

    @ExceptionHandler(AdapterProtocolException.class)
    public ResponseEntity<AdapterErrorResponse> handle(AdapterProtocolException error) {
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AdapterErrorResponse(
                EventEnvelope.CURRENT_PROTOCOL_VERSION,
                new AdapterProtocolError(
                        error.code(),
                        error.getMessage(),
                        error.retryable(),
                        error.details())));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<AdapterErrorResponse> handleInvalidInput(
            ServerWebInputException error) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AdapterErrorResponse(
                        EventEnvelope.CURRENT_PROTOCOL_VERSION,
                        new AdapterProtocolError(
                                "INVALID_REQUEST",
                                "request body is invalid",
                                false,
                                java.util.Map.of())));
    }
}
