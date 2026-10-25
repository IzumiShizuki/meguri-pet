package com.meguri.core.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Server-side, owner-scoped TimePrism Todo writer with durable request reconciliation. */
@Service
public final class TimePrismTodoWriter {
    private static final String DEFAULT_ORIGIN = "https://site.shizuki.online";
    private static final String AUTH_PATH = "/api/v1/auth/tokens";
    private static final String TODOS_PATH = "/api/v1/light-apps/todos";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_MINUTE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<Integer> DEFINITE_REJECTIONS = Set.of(400, 401, 403, 404, 405, 422, 429);
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_JOURNAL_ENTRIES = 5000;

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Path journalFile;
    private final String ownerEmailFile;
    private final String ownerPasswordFile;
    private final String configuredOrigin;
    private final long ownerUserId;
    private final long httpTimeoutMs;

    public TimePrismTodoWriter(
            ObjectMapper mapper,
            @Value("${MEGURI_REMINDER_DIR:${user.home}/.meguri/reminders}") String directory,
            @Value("${MEGURI_REMINDER_OWNER_SHIZUKI_USER_ID:0}") long ownerUserId,
            @Value("${MEGURI_TIMEPRISM_OWNER_EMAIL_FILE:}") String ownerEmailFile,
            @Value("${MEGURI_TIMEPRISM_OWNER_PASSWORD_FILE:}") String ownerPasswordFile,
            @Value("${MEGURI_TIMEPRISM_ORIGIN:}") String configuredOrigin) {
        this(mapper, directory, ownerUserId, ownerEmailFile, ownerPasswordFile, configuredOrigin, 15_000L);
    }

    @Autowired
    public TimePrismTodoWriter(
            ObjectMapper mapper,
            @Value("${MEGURI_REMINDER_DIR:${user.home}/.meguri/reminders}") String directory,
            @Value("${MEGURI_REMINDER_OWNER_SHIZUKI_USER_ID:0}") long ownerUserId,
            @Value("${MEGURI_TIMEPRISM_OWNER_EMAIL_FILE:}") String ownerEmailFile,
            @Value("${MEGURI_TIMEPRISM_OWNER_PASSWORD_FILE:}") String ownerPasswordFile,
            @Value("${MEGURI_TIMEPRISM_ORIGIN:}") String configuredOrigin,
            @Value("${MEGURI_TIMEPRISM_HTTP_TIMEOUT_MS:15000}") long httpTimeoutMs) {
        if (httpTimeoutMs <= 0) throw new IllegalArgumentException("TimePrism HTTP timeout must be positive");
        this.mapper = mapper;
        this.journalFile = Path.of(directory).toAbsolutePath().normalize()
                .resolve("timeprism-todo-writes.json");
        this.ownerUserId = ownerUserId;
        this.ownerEmailFile = ownerEmailFile == null ? "" : ownerEmailFile.trim();
        this.ownerPasswordFile = ownerPasswordFile == null ? "" : ownerPasswordFile.trim();
        this.configuredOrigin = configuredOrigin == null || configuredOrigin.isBlank()
                ? DEFAULT_ORIGIN : configuredOrigin.trim();
        this.httpTimeoutMs = httpTimeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public synchronized TimePrismTodoWriteReceipt create(TimePrismTodoCreateRequest request) {
        if (ownerUserId <= 0) throw new WriterNotConfiguredException();
        String requestId = validateRequestId(request.requestId());
        String title = validateTitle(request.title());
        LocalDateTime dueAt = parseFutureDueAt(request.dueAt());
        String dueAtText = dueAt.format(LOCAL_MINUTE);
        String fingerprint = fingerprint(ownerUserId, title, dueAtText);
        WriteJournal journal = readJournal();
        WriteEntry existing = journal.entries().get(requestId);
        if (existing != null && (!existing.fingerprint().equals(fingerprint)
                || existing.ownerUserId() != ownerUserId)) {
            throw new RequestConflictException();
        }
        if (existing != null && "confirmed".equals(existing.state())) {
            return receipt(requestId, "confirmed", existing.todo());
        }
        if (existing == null && journal.entries().values().stream().anyMatch(entry ->
                "uncertain".equals(entry.state()) && entry.ownerUserId() == ownerUserId
                        && entry.fingerprint().equals(fingerprint))) {
            throw new PendingDuplicateException();
        }

        Credentials credentials = readCredentials();
        String origin = normalizeOrigin(configuredOrigin);
        String accessToken = login(origin, credentials);

        if (existing != null && "uncertain".equals(existing.state())) {
            return reconcile(origin, accessToken, requestId, title, dueAtText, existing, journal);
        }

        WriteEntry uncertain = new WriteEntry(requestId, fingerprint, ownerUserId, "uncertain",
                System.currentTimeMillis(), null);
        journal.entries().put(requestId, uncertain);
        writeJournal(journal);
        try {
            JsonNode raw = exchange(origin, "POST", TODOS_PATH, accessToken, Map.ofEntries(
                    Map.entry("title", title),
                    Map.entry("detail", requestMarker(requestId)),
                    Map.entry("priority", "MEDIUM"),
                    Map.entry("done", false),
                    Map.entry("due_at", dueAtText + ":00"),
                    Map.entry("show_on_calendar", true),
                    Map.entry("time_precision", "MINUTE"),
                    Map.entry("timing_mode", "DEADLINE"),
                    Map.entry("reminder_enabled", true),
                    Map.entry("deadline_remind_value", 0),
                    Map.entry("deadline_remind_unit", "MINUTE")));
            Todo todo = normalizeTodo(raw);
            verify(todo, requestId, title, dueAtText);
            WriteEntry confirmed = new WriteEntry(requestId, fingerprint, ownerUserId, "confirmed",
                    System.currentTimeMillis(), todo.summary());
            journal.entries().put(requestId, confirmed);
            writeJournal(journal);
            return receipt(requestId, "confirmed", todo.summary());
        } catch (RemoteFailure failure) {
            if (failure.definiteRejection()) {
                journal.entries().put(requestId, new WriteEntry(requestId, fingerprint, ownerUserId,
                        "rejected", System.currentTimeMillis(), null));
                writeJournal(journal);
                throw new SiteRejectedException(failure.statusCode());
            }
            return receipt(requestId, "uncertain", null);
        } catch (IllegalArgumentException failure) {
            // A successful HTTP response with an unexpected body may still represent a committed write.
            return receipt(requestId, "uncertain", null);
        }
    }

    private TimePrismTodoWriteReceipt reconcile(
            String origin, String token, String requestId, String title, String dueAt,
            WriteEntry existing, WriteJournal journal) {
        List<Todo> matches;
        try {
            matches = listTodos(origin, token).stream()
                    .filter(todo -> requestMarker(requestId).equals(todo.detail()))
                    .toList();
        } catch (RemoteFailure failure) {
            return receipt(requestId, "uncertain", null);
        }
        if (matches.size() != 1) return receipt(requestId, "uncertain", null);
        Todo todo = matches.get(0);
        try {
            verify(todo, requestId, title, dueAt);
        } catch (IllegalArgumentException failure) {
            return receipt(requestId, "uncertain", null);
        }
        WriteEntry confirmed = new WriteEntry(requestId, existing.fingerprint(), ownerUserId,
                "confirmed", System.currentTimeMillis(), todo.summary());
        journal.entries().put(requestId, confirmed);
        writeJournal(journal);
        return receipt(requestId, "confirmed", todo.summary());
    }

    private String login(String origin, Credentials credentials) {
        JsonNode payload;
        try {
            payload = exchange(origin, "POST", AUTH_PATH, "", Map.of(
                    "grant_type", "EMAIL_PASSWORD",
                    "email", credentials.email(),
                    "password", credentials.password()));
        } catch (RemoteFailure failure) {
            if (failure.definiteRejection()) throw new SiteRejectedException(failure.statusCode());
            throw new SiteUnavailableException();
        }
        String accessToken = text(payload, "accessToken", "access_token");
        long returnedUserId = number(payload, "userId", "user_id");
        String resultType = text(payload, "resultType", "result_type");
        if (!"TOKEN_ISSUED".equals(resultType) || accessToken.isBlank() || returnedUserId != ownerUserId) {
            throw new WriterNotConfiguredException();
        }
        return accessToken;
    }

    private List<Todo> listTodos(String origin, String token) {
        JsonNode data = exchange(origin, "GET", TODOS_PATH, token, null);
        if (data == null || !data.isArray()) throw new RemoteFailure(0, false);
        List<Todo> todos = new ArrayList<>();
        for (JsonNode raw : data) {
            try {
                todos.add(normalizeTodo(raw));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed unrelated Todo rows; the marker match must still verify fully.
            }
        }
        return todos;
    }

    private JsonNode exchange(String origin, String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofMillis(httpTimeoutMs))
                    .header("Accept", "application/json");
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            AtomicReference<BoundedBodySubscriber> subscriberReference = new AtomicReference<>();
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(builder.build(), responseInfo -> {
                BoundedBodySubscriber subscriber = new BoundedBodySubscriber(MAX_RESPONSE_BYTES);
                subscriberReference.set(subscriber);
                return subscriber;
            });
            HttpResponse<byte[]> response;
            try {
                // sendAsync completes only after the subscriber consumes the full response body.
                response = pending.get(httpTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException failure) {
                pending.cancel(true);
                BoundedBodySubscriber subscriber = subscriberReference.get();
                if (subscriber != null) subscriber.cancel();
                throw new RemoteFailure(0, false);
            } catch (InterruptedException failure) {
                pending.cancel(true);
                BoundedBodySubscriber subscriber = subscriberReference.get();
                if (subscriber != null) subscriber.cancel();
                throw failure;
            } catch (ExecutionException failure) {
                throw new RemoteFailure(0, false);
            }
            byte[] bytes = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RemoteFailure(response.statusCode(), DEFINITE_REJECTIONS.contains(response.statusCode()));
            }
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) throw new RemoteFailure(response.statusCode(), false);
            JsonNode data = root.get("data");
            return data == null || data.isNull() ? root : data;
        } catch (RemoteFailure failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RemoteFailure(0, false);
        } catch (IOException | IllegalArgumentException failure) {
            throw new RemoteFailure(0, false);
        }
    }

    private Credentials readCredentials() {
        return new Credentials(readSecretFile(ownerEmailFile), readSecretFile(ownerPasswordFile));
    }

    private static String readSecretFile(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) throw new WriterNotConfiguredException();
        try {
            Path path = Path.of(configuredPath);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new WriterNotConfiguredException();
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (value.isBlank() || value.length() > 4096) throw new WriterNotConfiguredException();
            return value;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof WriterNotConfiguredException notConfigured) throw notConfigured;
            throw new WriterNotConfiguredException();
        }
    }

    private static String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equalsIgnoreCase(host) || "::1".equals(host);
            if (host == null || !("https".equalsIgnoreCase(uri.getScheme())
                    || (loopback && "http".equalsIgnoreCase(uri.getScheme())))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("invalid TimePrism origin");
            }
            int port = uri.getPort();
            String normalizedHost = host.contains(":") && !host.startsWith("[")
                    ? "[" + host + "]" : host;
            return uri.getScheme().toLowerCase() + "://" + normalizedHost
                    + (port < 0 ? "" : ":" + port);
        } catch (RuntimeException failure) {
            throw new WriterNotConfiguredException();
        }
    }

    private WriteJournal readJournal() {
        if (!Files.isRegularFile(journalFile)) return new WriteJournal(1, new LinkedHashMap<>());
        try {
            WriteJournal journal = mapper.readValue(journalFile.toFile(), WriteJournal.class);
            if (journal.version() != 1 || journal.entries() == null) throw new IOException("invalid write journal");
            return new WriteJournal(1, new LinkedHashMap<>(journal.entries()));
        } catch (IOException | RuntimeException failure) {
            throw new WriterStorageException();
        }
    }

    private void writeJournal(WriteJournal journal) {
        Path temporary = null;
        try {
            prune(journal.entries());
            Files.createDirectories(journalFile.getParent());
            temporary = Files.createTempFile(journalFile.getParent(), "timeprism-write-", ".tmp");
            mapper.writeValue(temporary.toFile(), journal);
            try {
                Files.move(temporary, journalFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, journalFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            throw new WriterStorageException();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private static void prune(Map<String, WriteEntry> entries) {
        while (entries.size() >= MAX_JOURNAL_ENTRIES) {
            String removable = entries.values().stream()
                    .filter(entry -> !"uncertain".equals(entry.state()))
                    .min((left, right) -> Long.compare(left.updatedAt(), right.updatedAt()))
                    .map(WriteEntry::requestId)
                    .orElseThrow(WriterStorageException::new);
            entries.remove(removable);
        }
    }

    private static String validateRequestId(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("invalid request ID");
        return UUID.fromString(value).toString();
    }

    private static String validateTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Todo title");
        }
        return value;
    }

    private static LocalDateTime parseFutureDueAt(String value) {
        try {
            LocalDateTime dueAt = LocalDateTime.parse(value, LOCAL_MINUTE);
            if (!dueAt.atZone(SHANGHAI).toInstant().isAfter(Instant.now())) {
                throw new IllegalArgumentException("dueAt must be in the future");
            }
            return dueAt;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("dueAt must be a future Shanghai local minute", failure);
        }
    }

    private static String fingerprint(long ownerId, String title, String dueAt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((ownerId + "\n" + title + "\n" + dueAt).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String requestMarker(String requestId) {
        return "meguri-request:" + requestId;
    }

    private static Todo normalizeTodo(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalArgumentException("invalid Todo response");
        return new Todo(number(raw, "todoId", "todo_id"), text(raw, "title"), text(raw, "detail"),
                text(raw, "dueAt", "due_at"), bool(raw, "done"),
                bool(raw, "reminderEnabled", "reminder_enabled"),
                text(raw, "timePrecision", "time_precision").toUpperCase(),
                text(raw, "timingMode", "timing_mode").toUpperCase(),
                integer(raw, "deadlineRemindValue", "deadline_remind_value"),
                text(raw, "deadlineRemindUnit", "deadline_remind_unit").toUpperCase());
    }

    private static void verify(Todo todo, String requestId, String title, String dueAt) {
        if (todo.todoId() <= 0 || !requestMarker(requestId).equals(todo.detail())
                || !title.equals(todo.title()) || todo.done()
                || !(dueAt + ":00").equals(todo.dueAt())
                || !todo.reminderEnabled() || !"MINUTE".equals(todo.timePrecision())
                || !"DEADLINE".equals(todo.timingMode()) || todo.deadlineRemindValue() != 0
                || !"MINUTE".equals(todo.deadlineRemindUnit())) {
            throw new IllegalArgumentException("TimePrism did not confirm the requested reminder fields");
        }
    }

    private static String text(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static long number(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null || !value.canConvertToLong() ? 0 : value.asLong();
    }

    private static int integer(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null || !value.canConvertToInt() ? Integer.MIN_VALUE : value.asInt();
    }

    private static boolean bool(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static JsonNode field(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null) return value;
        }
        return null;
    }

    private static TimePrismTodoWriteReceipt receipt(String requestId, String state, TodoSummary todo) {
        return new TimePrismTodoWriteReceipt(requestId, state,
                todo == null ? null : todo.todoId(),
                todo == null ? null : todo.title(),
                todo == null ? null : LocalDateTime.parse(todo.dueAt()).format(LOCAL_MINUTE));
    }

    private record Credentials(String email, String password) { }
    private record Todo(long todoId, String title, String detail, String dueAt, boolean done,
            boolean reminderEnabled, String timePrecision, String timingMode,
            int deadlineRemindValue, String deadlineRemindUnit) {
        private TodoSummary summary() { return new TodoSummary(todoId, title, dueAt); }
    }
    private record TodoSummary(long todoId, String title, String dueAt) { }
    private record WriteEntry(String requestId, String fingerprint, long ownerUserId,
            String state, long updatedAt, TodoSummary todo) { }
    private record WriteJournal(int version, Map<String, WriteEntry> entries) { }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private int received;

        private BoundedBodySubscriber(int maximumBytes) { this.maximumBytes = maximumBytes; }

        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value)) {
                value.cancel();
                return;
            }
            if (body.isDone()) {
                value.cancel();
                return;
            }
            value.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) return;
            for (ByteBuffer item : items) {
                int size = item.remaining();
                if ((long) received + size > maximumBytes) {
                    cancelSubscription();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[size];
                item.get(chunk);
                bytes.writeBytes(chunk);
                received += size;
            }
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }

        @Override public void onComplete() { body.complete(bytes.toByteArray()); }

        private void cancel() {
            cancelSubscription();
            body.cancel(true);
        }

        private void cancelSubscription() {
            Flow.Subscription active = subscription.get();
            if (active != null) active.cancel();
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException { }

    private static final class RemoteFailure extends RuntimeException {
        private final int statusCode;
        private final boolean definiteRejection;
        private RemoteFailure(int statusCode, boolean definiteRejection) {
            this.statusCode = statusCode;
            this.definiteRejection = definiteRejection;
        }
        private int statusCode() { return statusCode; }
        private boolean definiteRejection() { return definiteRejection; }
    }

    public static final class WriterNotConfiguredException extends RuntimeException { }
    public static final class WriterStorageException extends RuntimeException { }
    public static final class RequestConflictException extends RuntimeException { }
    public static final class PendingDuplicateException extends RuntimeException { }
    public static final class SiteUnavailableException extends RuntimeException { }
    public static final class SiteRejectedException extends RuntimeException {
        private final int statusCode;
        private SiteRejectedException(int statusCode) { this.statusCode = statusCode; }
        public int statusCode() { return statusCode; }
    }
}
