package com.meguri.core.reminder;

import com.meguri.core.security.CoreIdentityVerifier;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/reminders/timeprism")
public final class TimePrismReminderController {
    private final TimePrismReminderStore store;
    private final CoreIdentityVerifier identity;
    private final long ownerUserId;

    public TimePrismReminderController(TimePrismReminderStore store, CoreIdentityVerifier identity,
            @Value("${MEGURI_REMINDER_OWNER_SHIZUKI_USER_ID:0}") long ownerUserId) {
        this.store = store;
        this.identity = identity;
        this.ownerUserId = ownerUserId;
    }

    @PostMapping
    public Mono<TimePrismReminderEvent> upload(ServerWebExchange exchange,
            @Valid @RequestBody TimePrismReminderUpload upload) {
        requireClient(exchange, "desktop_pet", "shizuki-" + ownerUserId);
        if (upload.shizukiUserId() != ownerUserId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reminder account is not the configured owner");
        return Mono.fromCallable(() -> store.save(upload))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage()));
    }

    @GetMapping
    public Mono<TimePrismReminderFeed> feed(ServerWebExchange exchange,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        requireClient(exchange, "astrbot", "reminder-poller");
        return Mono.fromCallable(() -> store.feed(ownerUserId, after, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()));
    }

    private void requireClient(ServerWebExchange exchange, String clientId, String userId) {
        if (ownerUserId <= 0 || !identity.required())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "owner reminders are not configured");
        CoreIdentityVerifier.Identity actual = identity.verifyScope(exchange, userId, clientId, null);
        if (!clientId.equals(actual.clientId()) || !userId.equals(actual.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reminder client identity is invalid");
        if ("desktop_pet".equals(clientId) && !"desktop-timeprism-reminders".equals(actual.sessionId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reminder desktop session is invalid");
    }
}
