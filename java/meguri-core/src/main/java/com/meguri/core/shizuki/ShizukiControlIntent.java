package com.meguri.core.shizuki;

import java.util.List;
import java.util.Locale;

/** Narrow deterministic signal for an explicit user request to operate Shizuki. */
public final class ShizukiControlIntent {
    private static final List<String> APPLICATION_NAMES = List.of("shizuki", "式月");
    private static final List<String> ACTION_TERMS = List.of(
            "打开", "显示", "隐藏", "聚焦", "最小化", "切换", "播放", "暂停",
            "下一首", "上一首", "音乐", "导航", "前往", "页面", "状态", "运行",
            "看看", "控制",
            "open", "show", "hide", "focus", "minimize", "toggle", "play", "pause",
            "next", "previous", "music", "navigate", "status", "state", "control");

    private ShizukiControlIntent() { }

    public static boolean isExplicitRequest(String message) {
        String normalized = message == null
                ? "" : message.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || APPLICATION_NAMES.stream().noneMatch(normalized::contains)) {
            return false;
        }
        return ACTION_TERMS.stream().anyMatch(normalized::contains);
    }
}
