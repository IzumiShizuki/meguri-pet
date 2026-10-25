package com.meguri.core.reminder;

public record TimePrismTodoWriteReceipt(
        String requestId,
        String state,
        Long todoId,
        String title,
        String dueAt) {
}
