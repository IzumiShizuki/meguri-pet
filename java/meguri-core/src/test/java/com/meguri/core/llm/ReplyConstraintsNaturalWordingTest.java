package com.meguri.core.llm;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.meguri.core.llm.ReplyConstraintProviderRegressionTest.*;

class ReplyConstraintsNaturalWordingTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "不要联网，也不要查询实际天气。天气 API 文档里 temperature 和 humidity 两个 JSON 字段通常代表什么？用一句话说明。",
        "今天只是虚构聊天验收，我把桌上的纸鹤折好了。不要联网，不需要提醒睡觉；自然地接一句话就好。",
        "请用一句话解释光合作用。", "请用一个句子说明原理。",
        "用1句话回答。", "只用一个句子描述叶子。",
        "接一句话就好。", "自然接一句话即可。", "请自然地接一句话。", "用一句话说明"
    })
    void explicitNaturalWordingEnforcesOneSentence(String input) {
        var constraint = ReplyConstraints.from(input);
        assertTrue(constraint.oneSentence());
        assertEquals("sentence_count=2", constraint.violation("第一句。第二句。"));
        assertEquals("", constraint.violation("只有这一句。"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "他说用一句话说明原理。", "我喜欢自然地接一句话就好的风格。",
        "不要用一句话说明，请详细回答。", "不要只用一句话说明。",
        "请评价“用一句话说明原理”这个要求。",
        "请评价‘自然地接一句话就好。’的语气。",
        "> 用一句话说明原理。\n请分析引用。",
        "```text\n用一个句子解释。\n```\n请分析代码。",
        "用一句话说明原理。取消上面的句数限制。",
        "用一句话分别介绍三个角色。", "用一个句子描述每个角色。",
        "请自然地接一句话给每个人。",
        "用一句话说明原因，再用一句话给出建议。",
        "用一句话说明原因。用一句话给出建议。"
    })
    void sourceNegativeAndAmbiguousWordingDoesNotImposeGlobalLimit(String input) {
        assertFalse(ReplyConstraints.from(input).active());
    }

    @Test
    void quotedPerItemLanguageDoesNotCancelTheOuterConstraint() {
        assertTrue(ReplyConstraints.from("用一句话解释‘分别介绍每个角色’这个要求。").oneSentence());
    }

    @Test
    void weatherDocumentationFailureIsRegeneratedOnceBeforeDelivery() {
        var model = new ScriptedModel(
            "temperature 表示温度，humidity 表示湿度。按你说的，没有联网。",
            "temperature 表示温度，humidity 表示湿度。");
        assertEquals("temperature 表示温度，humidity 表示湿度。",
            answer(provider(model), "天气 API 中这两个字段是什么意思？用一句话说明。", List.of()));
        assertEquals(2, model.requests.size());
    }

    @Test
    void conversationalOneSentenceIsDeliveredAsOneValidatedDelta() {
        var model = new ScriptedModel("纸鹤折好啦。让我看看。", "那只纸鹤像是已经准备好起飞了呢。");
        assertEquals(List.of("那只纸鹤像是已经准备好起飞了呢。"),
            provider(model).stream(turn("纸鹤折好了；自然地接一句话就好。"),
                STATE, List.of(), List.of(), List.of(), List.of()).collectList().block());
        assertEquals(2, model.requests.size());
    }
}
