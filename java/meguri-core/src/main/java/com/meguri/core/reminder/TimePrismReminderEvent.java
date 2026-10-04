package com.meguri.core.reminder;

public record TimePrismReminderEvent(
        long sequence,
        String eventId,
        long shizukiUserId,
        long todoId,
        String title,
        String dueAt,
        long triggerAt,
        String kind,
        long publishedAt) {
}
