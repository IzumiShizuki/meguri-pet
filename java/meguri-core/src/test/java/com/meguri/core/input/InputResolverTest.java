package com.meguri.core.input;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputResolverTest {
    @Test
    void leavesOrdinaryDialogueUnchanged() {
        InputResolution result = InputResolver.resolve("今天辛苦了呀");

        assertThat(result.kind()).isEqualTo("normal");
        assertThat(result.message()).isEqualTo("今天辛苦了呀");
    }

    @Test
    void resolvesAllowListedWeatherCommand() {
        InputResolution result = InputResolver.resolve(" #天气 ");

        assertThat(result.kind()).isEqualTo("command");
        assertThat(result.command()).isEqualTo("weather");
        assertThat(result.arguments()).isBlank();
    }

    @Test
    void resolvesBillingOnlyAsAnExplicitShortcut() {
        InputResolution result = InputResolver.resolve("#账单");
        assertThat(result.kind()).isEqualTo("command");
        assertThat(result.command()).isEqualTo("billing");
    }

    @Test
    void requiresSearchKeywordsAndRejectsUnknownCommands() {
        assertThat(InputResolver.resolve("#搜索").error()).contains("关键词");
        assertThat(InputResolver.resolve("#代码 修复登录").error()).contains("未知快捷指令");
    }

    @Test
    void turnsTildeIntoEditableEngineeringDraftWithoutExecution() {
        InputResolution result = InputResolver.resolve("~修复登录超时");

        assertThat(result.kind()).isEqualTo("preprocess");
        assertThat(result.message()).contains("目标：修复登录超时");
        assertThat(result.preview()).contains("不会自动执行代码或调用 Codex");
    }

    @Test
    void allowsEscapingBothPrefixesIntoOrdinaryDialogue() {
        assertThat(InputResolver.resolve("##天气").message()).isEqualTo("#天气");
        assertThat(InputResolver.resolve("~~不是工程任务").message()).isEqualTo("~不是工程任务");
    }
}
