package com.meguri.core.skill;

import com.meguri.core.websearch.WebSearchPolicy;

import java.util.regex.Pattern;

/** Narrow read-only routing signal; it never supplies capability authority. */
public final class PublicSkillReadIntent {
    private static final Pattern QUOTED = Pattern.compile(
            "(?s)```.*?```|`[^`]*`|\"[^\"]*\"|“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』");
    private static final Pattern COMMUNITY = Pattern.compile("魔搭(?:社区)?|\\bmodelscope\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL = Pattern.compile("技能|\\bskills?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_ID = Pattern.compile(
            "(?<![A-Za-z0-9_./:@-])@?[A-Za-z0-9][A-Za-z0-9._-]{0,127}/"
                    + "[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?![A-Za-z0-9._/-])");
    private static final Pattern DIRECT_READ = Pattern.compile(
            "^(?:(?:请你?|麻烦你?|帮我|替我|为我|劳驾|能否|能不能|可以帮我|please|can you|could you)\\s*)*"
                    + "(?:(?:去|到|在|从)\\s*)?"
                    + "(?:(?:魔搭(?:社区)?|modelscope)(?:\\s*(?:community|社区))?\\s*(?:上|里|中|的)?\\s*)?"
                    + "(?:(?:帮我|替我|为我)\\s*)?"
                    + "(?:搜索|搜寻|寻找|查找|搜一下|查一下|查询|查阅|核实|查看|检索|找一下|找|评估|"
                    + "(?:search|find|look up|check|inspect|verify|browse)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPLEMENTATION_DISCUSSION = Pattern.compile(
            "实现|原理|调用流程|(?:如何|怎么)(?:使用|调用|实现|触发)|"
                    + "\\b(?:how to|implementation|implement|explain|describe|quote)\\b", Pattern.CASE_INSENSITIVE);

    private PublicSkillReadIntent() { }

    public static boolean isExplicitRequest(String message) {
        if (message == null || message.isBlank() || WebSearchPolicy.isOffline(message)) return false;
        String unquoted = QUOTED.matcher(message).replaceAll(" ")
                .replaceAll("(?m)^\\s*>[^\\r\\n]*", " ").trim();
        if (!COMMUNITY.matcher(unquoted).find()
                || !(SKILL.matcher(unquoted).find() || EXTERNAL_ID.matcher(unquoted).find())) return false;
        // Require a direct command, not an explanation of or a quotation containing one.
        return DIRECT_READ.matcher(unquoted).find()
                && !IMPLEMENTATION_DISCUSSION.matcher(unquoted).find();
    }
}
