package com.meguri.core.input;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback API used by desktop and future adapters before submitting a turn. */
@RestController
@RequestMapping("/v1/input")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class InputController {
    @PostMapping(path = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InputResolution resolve(@RequestBody InputResolveRequest request) {
        return InputResolver.resolve(request == null ? null : request.message());
    }
}
