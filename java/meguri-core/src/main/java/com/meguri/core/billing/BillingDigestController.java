package com.meguri.core.billing;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only endpoint exposing the safe Qianji post-sync digest. */
@RestController
@RequestMapping("/v1/daily/billing")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class BillingDigestController {
    private final BillingDigestReader reader;

    public BillingDigestController(BillingDigestReader reader) {
        this.reader = reader;
    }

    @GetMapping(path = "/briefing", produces = MediaType.APPLICATION_JSON_VALUE)
    public BillingBriefing briefing() {
        return reader.latest();
    }
}
