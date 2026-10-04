package com.meguri.core.reminder;

import java.util.List;

public record TimePrismReminderFeed(List<TimePrismReminderEvent> events, long latestSequence) {
}
