package com.meguri.core.conversation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Loopback-only boundary endpoint used after an idle-gap candidate has enough user turns. */
@RestController
@RequestMapping("/v1/conversations")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class ConversationBoundaryController {
    private final ConversationBoundaryService service;

    public ConversationBoundaryController(ConversationBoundaryService service) { this.service = service; }

    @PostMapping(path = "/boundary", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConversationBoundaryDecision> decide(@RequestBody ConversationBoundaryRequest request) {
        return service.decide(request);
    }
}
