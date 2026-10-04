package com.meguri.core.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Durable, bounded inbox for owner TimePrism reminder occurrences. */
@Service
public final class TimePrismReminderStore {
    private static final int MAX_EVENTS = 1000;
    private static final long RETENTION_MS = 7L * 24 * 60 * 60_000;
    private final ObjectMapper mapper;
    private final Path file;

    public TimePrismReminderStore(ObjectMapper mapper,
            @Value("${MEGURI_REMINDER_DIR:${user.home}/.meguri/reminders}")
            String directory) {
        this.mapper = mapper;
        this.file = Path.of(directory).toAbsolutePath().normalize().resolve("timeprism-inbox.json");
    }

    public synchronized TimePrismReminderEvent save(TimePrismReminderUpload upload) throws IOException {
        long now = System.currentTimeMillis();
        if (upload.triggerAt() < now - RETENTION_MS || upload.triggerAt() > now + 60 * 60_000
                || upload.queuedAt() < now - RETENTION_MS || upload.queuedAt() > now + 60_000) {
            throw new IllegalArgumentException("reminder occurrence is outside the accepted time window");
        }
        if (!upload.title().equals(upload.title().trim()) || upload.title().chars().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalArgumentException("reminder title contains invalid characters");
        }
        try {
            LocalDateTime.parse(upload.dueAt());
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("dueAt is not a valid local date/time", error);
        }
        Inbox current = read();
        for (TimePrismReminderEvent event : current.events()) {
            if (event.eventId().equals(upload.eventId())) {
                if (event.shizukiUserId() != upload.shizukiUserId() || event.todoId() != upload.todoId()
                        || !event.title().equals(upload.title()) || !event.dueAt().equals(upload.dueAt())
                        || event.triggerAt() != upload.triggerAt() || !event.kind().equals(upload.kind())) {
                    throw new IllegalArgumentException("eventId already belongs to a different reminder");
                }
                return event;
            }
        }
        List<TimePrismReminderEvent> events = retained(current.events(), now);
        TimePrismReminderEvent created = new TimePrismReminderEvent(current.latestSequence() + 1,
                upload.eventId(), upload.shizukiUserId(), upload.todoId(), upload.title(),
                upload.dueAt(), upload.triggerAt(), upload.kind(), now);
        events.add(created);
        if (events.size() > MAX_EVENTS) events = new ArrayList<>(events.subList(events.size() - MAX_EVENTS, events.size()));
        write(new Inbox(created.sequence(), events));
        return created;
    }

    public synchronized TimePrismReminderFeed feed(long ownerUserId, long after, int limit) throws IOException {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid reminder feed cursor or limit");
        Inbox inbox = read();
        List<TimePrismReminderEvent> events = inbox.events().stream()
                .filter(event -> event.shizukiUserId() == ownerUserId && event.sequence() > after
                        && event.publishedAt() >= System.currentTimeMillis() - RETENTION_MS)
                .limit(limit).toList();
        return new TimePrismReminderFeed(events, inbox.latestSequence());
    }

    private Inbox read() throws IOException {
        if (!Files.isRegularFile(file)) return new Inbox(0, List.of());
        Inbox inbox = mapper.readValue(file.toFile(), Inbox.class);
        if (inbox.events() == null || inbox.latestSequence() < 0) throw new IOException("invalid reminder inbox");
        return inbox;
    }

    private List<TimePrismReminderEvent> retained(List<TimePrismReminderEvent> source, long now) {
        List<TimePrismReminderEvent> result = new ArrayList<>();
        for (TimePrismReminderEvent event : source)
            if (event.publishedAt() >= now - RETENTION_MS) result.add(event);
        return result;
    }

    private void write(Inbox inbox) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "timeprism-inbox-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), inbox);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Inbox(long latestSequence, List<TimePrismReminderEvent> events) { }
}
