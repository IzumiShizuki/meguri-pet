package com.meguri.core.shizuki;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShizukiControlIntentTest {

    @Test
    void recognizesExplicitChineseAndEnglishControlRequests() {
        assertThat(ShizukiControlIntent.isExplicitRequest("帮我打开 Shizuki 的音乐页")).isTrue();
        assertThat(ShizukiControlIntent.isExplicitRequest("让式月暂停音乐")).isTrue();
        assertThat(ShizukiControlIntent.isExplicitRequest("Show the Shizuki window")).isTrue();
        assertThat(ShizukiControlIntent.isExplicitRequest("Shizuki 现在运行吗？")).isTrue();
    }

    @Test
    void doesNotUpgradeOrdinaryConversationOrProductMentions() {
        assertThat(ShizukiControlIntent.isExplicitRequest("Shizuki 是我的个人网站项目")).isFalse();
        assertThat(ShizukiControlIntent.isExplicitRequest("今天想聊聊音乐")).isFalse();
        assertThat(ShizukiControlIntent.isExplicitRequest("你好呀")).isFalse();
        assertThat(ShizukiControlIntent.isExplicitRequest(null)).isFalse();
    }
}
