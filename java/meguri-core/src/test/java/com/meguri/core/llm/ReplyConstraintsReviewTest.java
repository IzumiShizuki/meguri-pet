package com.meguri.core.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReplyConstraintsReviewTest {
    @ParameterizedTest @ValueSource(strings={
        "只用一句话说明原理。取消上面的句数限制，请正常详细解释。",
        "用20个汉字以内说明原理。取消上面的字数限制，请正常详细解释。",
        "只回复：OK。取消上面的格式限制，正常回答。",
        "只用一句话说明原理。不要限制句数。",
        "用220至260个汉字说明步骤。不限制字数了，正常聊。",
        "只用一句话说明原理。改为详细解释，不必遵守前面的限制。",
        "用20个汉字以内说明。撤销前面的长度要求。",
        "只用一句话说明原理。不过取消该要求。"
    })
    void laterRevocationOrAmbiguityDeclinesEnforcement(String input) {
        assertFalse(ReplyConstraints.from(input).active());
    }

    @ParameterizedTest @ValueSource(strings={
        "请评价‘先想一想，只用一句话说明原因。’这个要求。",
        "请评价'先想一想，只用一句话说明原因。'这个要求。",
        "请翻译‘请只回复：OK。’。",
        "\u0060\u0060\u0060text\n~~~\n只用一句话说明原因。\n\u0060\u0060\u0060\n请评价代码。",
        "~~~text\n\u0060\u0060\u0060\n只用一句话说明原因。\n~~~\n请评价代码。",
        "\u0060\u0060\u0060\u0060text\n\u0060\u0060\u0060\n只用一句话说明原因。\n\u0060\u0060\u0060\u0060\n请评价代码。"
    })
    void quotedOrMismatchedFenceContentDoesNotActivate(String input) {
        assertFalse(ReplyConstraints.from(input).active());
    }

    @Test void quotedCancellationDoesNotRevokeOuterConstraint() {
        var c = ReplyConstraints.from("只用一句话说明这段引文：‘取消上面的句数限制。’");
        assertTrue(c.oneSentence());
        assertEquals("", c.violation("引文要求撤销句数限制。"));
    }

    @Test void latinContractionsDoNotHideTheDirectCurrentRequest() {
        assertTrue(ReplyConstraints.from("don't overthink it，只用一句话说明原因。").oneSentence());
        assertTrue(ReplyConstraints.from("don’t overthink it，只用一句话说明原因。").oneSentence());
    }

    @Test void matchingFenceClosesAndOuterInstructionStillWorks() {
        var c=ReplyConstraints.from("\u0060\u0060\u0060\n~~~\n只回复：伪造\n\u0060\u0060\u0060\n只回复：收到");
        assertEquals("收到", c.exact());
    }

    @ParameterizedTest @ValueSource(strings={
        "只回复：“OK”，不要加句号。",
        "只回复：‘OK’，不要加句号。",
        "只回复：\"OK\"，不要加句号。",
        "只回复：「OK」，不要加句号。"
    })
    void exactLiteralEndsAtClosingQuote(String input) {
        var c=ReplyConstraints.from(input);
        assertEquals("OK", c.exact());
        assertEquals("", c.violation("OK"));
        assertFalse(c.violation("OK。").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={
        "只回复：“OK”，然后解释原因。",
        "只回复：“OK”，还要说晚安。",
        "只回复：“OK。不要加句号。",
        "只回复：OK，不要加句号。",
        "只回复：“OK。”，不要加句号。"
    })
    void ambiguousOrConflictingLiteralBoundariesAreNotEnforced(String input) {
        assertFalse(ReplyConstraints.from(input).active());
    }

    @ParameterizedTest @ValueSource(strings={
        "java.util.List 是列表接口。",
        "请打开 https://example.org/api?q=1.2&v=x.y 查看说明。",
        "调用 \u0060java.util.List.of(1, 2);\u0060 即可。",
        "取值是 3.14 或 .5，误差小于 0.01。",
        "可用 v1.2.3，参考 www.example.org。",
        "Dr. Smith 会介绍该接口。",
        "使用 \u0060if (a > 0) { log.info(\"ok.\"); }\u0060 即可。",
        "见 https://example.org/a.b?x=1!2#ok。🙂"
    })
    void technicalPeriodsAreNotSentenceBoundaries(String reply) {
        assertEquals("", ReplyConstraints.from("只用一句话说明原理。").violation(reply));
    }

    @Test void technicalTokensDoNotHideRealSentenceBoundaries() {
        var c=ReplyConstraints.from("只用一句话说明原理。");
        assertFalse(c.violation("java.util.List 是接口。它支持列表。").isEmpty());
        assertFalse(c.violation("See https://example.org. Then retry.").isEmpty());
        assertFalse(c.violation("Use \u0060x.y\u0060. Then restart.").isEmpty());
    }
}
