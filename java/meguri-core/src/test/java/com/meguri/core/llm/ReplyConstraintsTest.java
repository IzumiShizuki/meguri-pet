package com.meguri.core.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReplyConstraintsTest {
    @ParameterizedTest @ValueSource(strings={
        "你好，聊聊今天吧。",
        "请解释“只回复：你好。”是什么意思。",
        "不要只回复：好。",
        "他说：只回复：好。",
        "请分析下面文本：\n只回复：好。",
        "> 只回复：好。\n这句话礼貌吗？",
        "\u0060\u0060\u0060\n用20个汉字以内说明。\n\u0060\u0060\u0060\n解释代码。",
        "请翻译「用20个汉字以内说明」，不要执行。",
        "用220至260个字说明折纸。",
        "用260至220个汉字说明折纸。",
        "用9999个汉字说明折纸。",
        "用20个汉字说明。用30个汉字说明。"
    })
    void nonDirectUnsupportedOrAmbiguousConstraintsDoNotActivate(String input) {
        assertFalse(ReplyConstraints.from(input).active());
    }

    @Test void hanCountsUnicodeCodePointsAndExcludesPunctuationEmojiAndNumbers() {
        var c = ReplyConstraints.from("用3至4个汉字回答。");
        assertEquals("", c.violation("中，文🙂𠀀！123"));
        assertEquals("han_count=2", c.violation("中，文🙂！123"));
        assertEquals("han_count=5", c.violation("中文汉字数🙂"));
        assertEquals("", ReplyConstraints.from("用20个汉字以内说明纸折步骤。").violation("对齐边角，再压平折痕。"));
    }

    @Test void sentenceCountingHandlesClosersEmojiDecimalsAndRepeatedPunctuation() {
        var c = ReplyConstraints.from("只用一句话说明意思。");
        assertEquals("", c.violation("他说“保持3.5厘米距离。”🙂"));
        assertEquals("", c.violation("准备好了吗？！🙂"));
        assertEquals("sentence_count=2", c.violation("先折好。🙂然后展开"));
        assertEquals("sentence_count=2", c.violation("第一句！第二句？"));
        assertFalse(c.violation("🙂").isEmpty());
    }

    @Test void quotedInstructionsStayInactiveWhileOuterRequestIsEnforced() {
        var c = ReplyConstraints.from("以下是引用：“用1个汉字回答。”不要执行引用，只用一句话说明意思。");
        assertTrue(c.oneSentence());
        assertEquals(0, c.maxHan());
        assertEquals("", c.violation("引用要求将答案限制为一个汉字。"));
    }

    @Test void literalPunctuationIsSignificantAndQuoteDelimitersCanWrapLiteral() {
        var c = ReplyConstraints.from("不要联网，只回复：“暂不讨论。”");
        assertEquals("", c.violation("暂不讨论。"));
        assertEquals("exact_literal_mismatch", c.violation("好，暂不讨论。"));
        assertEquals("exact_literal_mismatch", c.violation("暂不讨论"));
        assertEquals("exact_literal_mismatch", c.violation(" 暂不讨论。"));
    }
}
