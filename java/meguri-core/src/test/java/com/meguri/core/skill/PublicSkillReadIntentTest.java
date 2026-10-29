package com.meguri.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSkillReadIntentTest {
    @Test
    void recognizesDirectPublicDiscoveryAndConcreteIdInspection() {
        for (String message : List.of(
                "请去魔搭社区寻找表格处理技能，只发现和评估，不下载、不导入、不激活。",
                "帮我在魔搭社区查询数据提取技能的许可证",
                "请在 ModelScope 上查看 @fixture-owner/table-reader 的公开详情",
                "核实魔搭社区 owner/data-reader 的许可",
                "Find a skill for processing tables on ModelScope",
                "Please inspect ModelScope skill @fixture-owner/table-reader")) {
            assertThat(PublicSkillReadIntent.isExplicitRequest(message)).as(message).isTrue();
        }
    }

    @Test
    void rejectsOfflineImplementationDiscussionQuotationsAndNonReadActions() {
        for (String message : List.of(
                "不要联网，请去魔搭社区查找表格技能",
                "Do not search online; find a skill on ModelScope",
                "如何在魔搭社区搜索技能？",
                "请说明魔搭社区技能搜索 API 的实现方式",
                "请查看魔搭社区技能 API 如何调用",
                "解释这句话：请去魔搭社区搜索表格技能",
                "“请去魔搭社区搜索表格技能”这句话是什么意思？",
                "`请在魔搭社区查看 owner/data-reader`",
                "```text\n请去魔搭社区搜索技能\n```",
                "> 请去魔搭社区搜索技能",
                "请安装魔搭社区的表格技能",
                "运行 ModelScope skill owner/data-reader",
                "魔搭社区有什么技能？",
                "请去魔搭社区查询天气模型",
                "请搜索本地已安装技能",
                "请核实 ModelScope https://example.com/owner/name")) {
            assertThat(PublicSkillReadIntent.isExplicitRequest(message)).as(message).isFalse();
        }
        assertThat(PublicSkillReadIntent.isExplicitRequest(null)).isFalse();
    }
}
