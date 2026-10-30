package com.meguri.core.reminder;

import jakarta.validation.constraints.*;
import java.util.List;

/** Rule-local date bounds are explicit so an uncertain retry cannot move across midnight. */
public record TimePrismRecurringRuleCreateRequest(
        @NotBlank String requestId,
        @NotBlank @Size(max=200) String title,
        @NotEmpty @Size(max=7) List<Integer> weekdays,
        @NotBlank String time,
        @NotBlank String timeZoneId,
        @NotBlank String startAt,
        String endAt,
        @NotNull Integer deadlineRemindValue,
        @NotBlank String deadlineRemindUnit) { }
