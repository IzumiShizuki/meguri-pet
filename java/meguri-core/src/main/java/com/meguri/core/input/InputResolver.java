package com.meguri.core.input;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separates explicit actions from optional engineering-task shaping.
 *
 * <p>{@code #} only selects an allow-listed command. {@code ~} creates an
 * editable task draft and deliberately has no execution capability.
 * {@code @} requests a local-resource selection without reading the selected
 * file. All other input bypasses this layer unchanged.</p>
 */
public final class InputResolver {
    private static final Pattern EMBEDDED_RESOURCE = Pattern.compile(
            "(?<!\\S)@(?:\\\"([^\\\"]{1,200})\\\"|\\{([^{}]{1,200})}|([^\\s@]{1,200}))");
    private static final Pattern BARE_RESOURCE = Pattern.compile("(?<!\\S)@$");
    private static final Map<String, String> COMMANDS = Map.ofEntries(
            Map.entry("天气", "weather"), Map.entry("weather", "weather"),
            Map.entry("账单", "billing"), Map.entry("bill", "billing"), Map.entry("billing", "billing"),
            Map.entry("视频日报", "bilibili"), Map.entry("b站日报", "bilibili"), Map.entry("bilibili", "bilibili"),
            Map.entry("搜索", "search"), Map.entry("search", "search"),
            Map.entry("帮助", "help"), Map.entry("help", "help"), Map.entry("?", "help"));

    private InputResolver() { }

    public static InputResolution resolve(String raw) {
        String original = raw == null ? "" : raw;
        String trimmed = original.stripLeading();
        if (trimmed.startsWith("##")) return InputResolution.normal(original, trimmed.substring(1));
        if (trimmed.startsWith("~~")) return InputResolution.normal(original, trimmed.substring(1));
        if (trimmed.startsWith("@@")) return InputResolution.normal(original, trimmed.substring(1));
        if (trimmed.startsWith("#")) return resolveCommand(original, trimmed.substring(1));
        if (trimmed.startsWith("~")) return resolvePreprocess(original, trimmed.substring(1));
        if (trimmed.startsWith("@")) return InputResolution.resource(original, "", trimmed.substring(1).trim());

        Matcher resource = EMBEDDED_RESOURCE.matcher(original);
        if (resource.find()) {
            String query = firstNonBlank(resource.group(1), resource.group(2), resource.group(3));
            String message = (original.substring(0, resource.start()) + original.substring(resource.end()))
                    .replaceAll("\\s{2,}", " ").trim();
            return InputResolution.resource(original, message, query);
        }
        String withoutTrailingSpace = original.stripTrailing();
        Matcher bareResource = BARE_RESOURCE.matcher(withoutTrailingSpace);
        if (bareResource.find()) {
            return InputResolution.resource(original,
                    withoutTrailingSpace.substring(0, bareResource.start()).trim(), "");
        }
        return InputResolution.normal(original, original);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static InputResolution resolveCommand(String raw, String body) {
        String normalized = body.trim();
        if (normalized.isEmpty()) return InputResolution.error(raw, null,
                "请输入 #帮助、#天气、#账单、#视频日报 或 #搜索 关键词。");
        String[] parts = normalized.split("\\s+", 2);
        String requested = parts[0].toLowerCase(Locale.ROOT);
        String command = COMMANDS.get(requested);
        if (command == null) {
            return InputResolution.error(raw, requested,
                    "未知快捷指令。可用：#帮助、#天气、#账单、#视频日报、#搜索 关键词。");
        }
        String arguments = parts.length == 2 ? parts[1].trim() : "";
        if (command.equals("search") && arguments.isBlank()) {
            return InputResolution.error(raw, command, "#搜索 需要提供关键词，例如：#搜索 LangChain4j 文档");
        }
        if ((command.equals("weather") || command.equals("billing") || command.equals("bilibili"))
                && !arguments.isBlank()) {
            return InputResolution.error(raw, command, "该快捷指令当前不接受额外参数。");
        }
        return InputResolution.command(raw, command, arguments);
    }

    private static InputResolution resolvePreprocess(String raw, String body) {
        String objective = body.trim();
        if (objective.isEmpty()) {
            return InputResolution.error(raw, "preprocess", "~ 后请输入需要补全的工程任务，例如：~修复登录超时");
        }
        String expanded = "工程任务草稿\n"
                + "目标：" + objective + "\n"
                + "范围：待确认涉及的模块与接口；不修改无关模块。\n"
                + "验收：功能可用、异常路径明确，并补充对应测试。\n"
                + "待确认：影响目录、数据/权限边界、兼容要求与上线方式。";
        String preview = "已生成工程任务草稿。你可以编辑后再发送给 Meguri；该操作不会自动执行代码或调用 Codex。\n\n" + expanded;
        return InputResolution.preprocess(raw, expanded, preview);
    }
}
