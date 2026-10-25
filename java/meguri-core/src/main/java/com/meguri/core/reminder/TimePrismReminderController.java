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
    private final TimePrismTodoWriter todoWriter;
    private final CoreIdentityVerifier identity;
    private final long ownerUserId;

    public TimePrismReminderController(TimePrismReminderStore store, TimePrismTodoWriter todoWriter,
            CoreIdentityVerifier identity,
            @Value("${MEGURI_REMINDER_OWNER_SHIZUKI_USER_ID:0}") long ownerUserId) {
        this.store = store;
        this.todoWriter = todoWriter;
        this.identity = identity;
        this.ownerUserId = ownerUserId;
    }

    @PostMapping("/todos")
    public Mono<TimePrismTodoWriteReceipt> createTodo(ServerWebExchange exchange,
            @Valid @RequestBody TimePrismTodoCreateRequest request) {
        requireTodoWriter(exchange);
        return Mono.fromCallable(() -> todoWriter.create(request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(TimePrismTodoWriter.WriterNotConfiguredException.class,
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "TimePrism Todo writer is not configured"))
                .onErrorMap(TimePrismTodoWriter.WriterStorageException.class,
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "TimePrism Todo write journal is unavailable"))
                .onErrorMap(TimePrismTodoWriter.RequestConflictException.class,
                        error -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "requestId already belongs to different Todo content"))
                .onErrorMap(TimePrismTodoWriter.PendingDuplicateException.class,
                        error -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "an equivalent Todo write is still uncertain"))
                .onErrorMap(TimePrismTodoWriter.SiteRejectedException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                "TimePrism rejected the Todo write"))
                .onErrorMap(TimePrismTodoWriter.SiteUnavailableException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                "TimePrism is unavailable"))
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "invalid TimePrism Todo request"));
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
        if (ownerUserId <= 0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "owner reminders are not configured");
        String sessionId = "desktop_pet".equals(clientId)
                ? "desktop-timeprism-reminders" : "reminder-poller";
        CoreIdentityVerifier.Identity actual = identity.verifyPrivilegedScope(exchange, userId, clientId, sessionId);
        if (!clientId.equals(actual.clientId()) || !userId.equals(actual.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reminder client identity is invalid");
        if ("desktop_pet".equals(clientId) && !"desktop-timeprism-reminders".equals(actual.sessionId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reminder desktop session is invalid");
    }

    private void requireTodoWriter(ServerWebExchange exchange) {
        if (ownerUserId <= 0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "owner TimePrism Todo writing is not configured");
        String ownerIdentity = "shizuki-" + ownerUserId;
        CoreIdentityVerifier.Identity actual = identity.verifyPrivilegedScope(
                exchange, ownerIdentity, "astrbot", "timeprism-todo-writer");
        if (!"astrbot".equals(actual.clientId()) || !ownerIdentity.equals(actual.userId())
                || !"timeprism-todo-writer".equals(actual.sessionId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "TimePrism Todo writer identity is invalid");
        }
    }
}
