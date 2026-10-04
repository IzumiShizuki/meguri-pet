package com.meguri.core.reminder;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TimePrismReminderUpload(
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String eventId,
        @Min(1) long shizukiUserId,
        @Min(1) long todoId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?$") String dueAt,
        @Min(1) long triggerAt,
        @NotBlank @Pattern(regexp = "^(deadline|start)$") String kind,
        @NotNull @Min(1) Long queuedAt) {
}
