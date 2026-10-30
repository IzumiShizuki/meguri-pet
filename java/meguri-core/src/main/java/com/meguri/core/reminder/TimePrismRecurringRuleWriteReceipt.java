package com.meguri.core.reminder;

public record TimePrismRecurringRuleWriteReceipt(
        String requestId, String state, Long ruleId, String title, String cronExpr,
        String timeZoneId, String startAt, String endAt,
        Integer deadlineRemindValue, String deadlineRemindUnit) { }
