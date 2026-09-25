package com.meguri.core.document;

import java.util.Map;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Loopback endpoint reached only after a visible desktop confirmation action. */
@RestController
@RequestMapping("/v1/documents/edits")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class DocumentEditController {
    private final DocumentEditService edits = new DocumentEditService(DocumentEditPreviewStore.shared());

    @GetMapping(path = "/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> pending() {
        List<Map<String, Object>> previews = DocumentEditPreviewStore.shared().pendingEventData();
        return Mono.just(Map.of("document_edits", previews));
    }

    @PostMapping(path = "/{token}/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> apply(@PathVariable String token) {
        try {
            return Mono.just(edits.apply(token).toWire());
        } catch (DocumentEditingException error) {
            return Mono.just(Map.of("applied", false, "error", error.getMessage()));
        }
    }
}
