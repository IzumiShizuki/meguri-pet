package com.meguri.core.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ReplyConstraintsFollowupReviewTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "请评价‘don’t overthink it，只用一句话说明原因。’这个要求。",
            "请评价‘don't overthink it，只用一句话说明原因。’这个要求。",
            "请评价'don’t overthink it，只用一句话说明原因。'这个要求。",
            "请评价'don't overthink it，只用一句话说明原因。'这个要求。"
    })
    void contractionInsideQuotationDoesNotCloseIt(String input) {
        assertFalse(ReplyConstraints.from(input).active(), "quoted instruction must remain source data");
        assertEquals("已评价", ReplyConstraints.from(input + "只回复：已评价").exact(),
                "the actual closing quotation must still expose the following user instruction");
    }

    @Test
    void bareHanSentencesAreNotQualifiedTechnicalIdentifiers() {
        String reply = "先保存.再重启.";
        assertEquals(2, ReplyConstraints.sentenceCount(reply));
        assertEquals("sentence_count=2", ReplyConstraints.from("只用一句话说明原理。").violation(reply));
    }

    @Test
    void bareJavaQualifiedIdentifierRemainsProtected() {
        String reply = "java.util.List 是列表接口。";
        assertEquals(1, ReplyConstraints.sentenceCount(reply));
        assertEquals("", ReplyConstraints.from("只用一句话说明原理。").violation(reply));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\u0060先保存.再重启.\u0060",
            "\u0060\u0060\u0060text\n先保存.再重启.\n\u0060\u0060\u0060",
            "~~~text\n先保存.再重启.\n~~~"
    })
    void explicitCodeMarkersStillProtectHanContent(String code) {
        var constraints = ReplyConstraints.from("只用一句话说明原理。");
        assertEquals("", constraints.violation("示例为 " + code + "。"));
        assertEquals("sentence_count=2", constraints.violation("示例为 " + code + "。随后验证。"));
    }
}
