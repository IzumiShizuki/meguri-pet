package com.meguri.core.shizuki;

import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityRuntimeFacade;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit model-facing mappings for Shizuki protocol 1.x. */
public final class ShizukiControlCapabilities {
    public static final String REQUIRED_SCOPE = "shizuki:control";
    public static final String STATE_READ = "shizuki.state.read";

    private static final Map<String, String> DESTINATIONS = orderedMap(
            "home", "主页",
            "blog", "博客",
            "music", "音乐页",
            "apps", "应用页",
            "aiHub", "AI Hub",
            "profile", "个人资料页",
            "admin", "管理页",
            "author", "作者页");
    private static final Map<String, String> WINDOW_ACTIONS = orderedMap(
            "show", "显示窗口",
            "hide", "隐藏窗口",
            "focus", "聚焦窗口",
            "minimize", "最小化窗口",
            "toggleVisibility", "切换窗口可见状态");
    private static final Map<String, String> MUSIC_ACTIONS = orderedMap(
            "play", "开始播放音乐",
            "pause", "暂停音乐",
            "toggle", "切换音乐播放状态",
            "next", "播放下一首音乐",
            "previous", "播放上一首音乐");

    private final ShizukiControlClient client;

    public ShizukiControlCapabilities(ShizukiControlClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public void registerInto(CapabilityRuntimeFacade runtime) {
        Objects.requireNonNull(runtime, "runtime").registerBatch(registrations());
    }

    public List<CapabilityRuntimeFacade.CapabilityRegistration> registrations() {
        List<CapabilityRuntimeFacade.CapabilityRegistration> registrations = new ArrayList<>();
        registrations.add(new CapabilityRuntimeFacade.CapabilityRegistration(
                descriptor(STATE_READ, CapabilityDescriptor.Kind.READ_TOOL,
                        CapabilityDescriptor.SideEffect.READ),
                (input, context) -> client.readState()));
        DESTINATIONS.forEach((destination, label) -> registrations.add(registration(
                "shizuki.open." + capabilitySegment(destination),
                "app.navigate", Map.of("destination", destination),
                "Shizuki 已打开" + label + "。")));
        WINDOW_ACTIONS.forEach((action, label) -> registrations.add(registration(
                "shizuki.window." + capabilitySegment(action),
                "window." + action, Map.of(), "Shizuki 已" + label + "。")));
        MUSIC_ACTIONS.forEach((action, label) -> registrations.add(registration(
                "shizuki.music." + action,
                "music." + action, Map.of(), "Shizuki 已" + label + "。")));
        return List.copyOf(registrations);
    }

    private CapabilityRuntimeFacade.CapabilityRegistration registration(
            String capabilityId,
            String command,
            Map<String, Object> payload,
            String successSummary) {
        return new CapabilityRuntimeFacade.CapabilityRegistration(
                descriptor(capabilityId, CapabilityDescriptor.Kind.LOCAL_CONTROL_TOOL,
                        CapabilityDescriptor.SideEffect.EXTERNAL),
                (input, context) -> {
                    Map<String, Object> raw = client.execute(command, payload);
                    if (!"executed".equals(raw.get("status"))) return raw;
                    LinkedHashMap<String, Object> result = new LinkedHashMap<>(raw);
                    result.put("summary", successSummary);
                    return Map.copyOf(result);
                });
    }

    private static CapabilityDescriptor descriptor(
            String id,
            CapabilityDescriptor.Kind kind,
            CapabilityDescriptor.SideEffect sideEffect) {
        return new CapabilityDescriptor(
                id, "1", kind, "meguri-core",
                CapabilityDescriptor.Schema.empty(),
                new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of()),
                Set.of(REQUIRED_SCOPE), sideEffect,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(8), CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(2),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL,
                "local-shizuki://" + id,
                CapabilityDescriptor.Health.HEALTHY, false, 1,
                CapabilityDescriptor.NetworkPolicy.denied(), Set.of(),
                CapabilityDescriptor.CostPolicy.free(),
                CapabilityDescriptor.CachePolicy.disabled(),
                CapabilityDescriptor.IdempotencyPolicy.none());
    }

    private static String capabilitySegment(String protocolValue) {
        return switch (protocolValue) {
            case "aiHub" -> "ai_hub";
            case "toggleVisibility" -> "toggle_visibility";
            default -> protocolValue;
        };
    }

    private static Map<String, String> orderedMap(String... values) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
