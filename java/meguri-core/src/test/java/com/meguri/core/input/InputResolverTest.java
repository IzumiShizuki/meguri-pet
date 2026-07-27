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
    void resolvesBilibiliBrowserDigestOnlyAsAnExplicitShortcut() {
        InputResolution result = InputResolver.resolve("#视频日报");

        assertThat(result.kind()).isEqualTo("command");
        assertThat(result.command()).isEqualTo("bilibili");
        assertThat(InputResolver.resolve("#B站日报").command()).isEqualTo("bilibili");
        assertThat(InputResolver.resolve("#视频日报 昨天").error()).contains("不接受额外参数");
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
        assertThat(InputResolver.resolve("@@不是资源引用").message()).isEqualTo("@不是资源引用");
    }

    @Test
    void treatsLeadingAtSignAsAResourceQueryWithoutReadingIt() {
        InputResolution result = InputResolver.resolve("@季度总结 2026");

        assertThat(result.kind()).isEqualTo("resource");
        assertThat(result.arguments()).isEqualTo("季度总结 2026");
        assertThat(result.message()).isBlank();
        assertThat(result.preview()).contains("只发送文件元数据");
    }

    @Test
    void extractsStandaloneEmbeddedResourceReferencesButNotEmailAddresses() {
        InputResolution result = InputResolver.resolve("帮我总结 @{季度 报告} 的重点");

        assertThat(result.kind()).isEqualTo("resource");
        assertThat(result.arguments()).isEqualTo("季度 报告");
        assertThat(result.message()).isEqualTo("帮我总结 的重点");
        assertThat(InputResolver.resolve("请发到 me@example.com").kind()).isEqualTo("normal");
    }

    @Test
    void acceptsAnEmptyAtSignAsAnExplicitResourceSelectionMode() {
        InputResolution result = InputResolver.resolve("@");

        assertThat(result.kind()).isEqualTo("resource");
        assertThat(result.arguments()).isBlank();
        assertThat(InputResolver.resolve("帮我看一下 @  ").message()).isEqualTo("帮我看一下");
    }
}
