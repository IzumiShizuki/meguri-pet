package com.meguri.core.bilibili;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BilibiliSelectedVideoSummaryGatewayTest {
    @Test
    void explicitSelectionReturnsUnavailableWithoutFetchingContent() {
        BilibiliSelectedVideoSummaryRequest request = new BilibiliSelectedVideoSummaryRequest(
                List.of("BV1xx411c7mD"), "重点总结观点");

        BilibiliSelectedVideoSummaryResult result =
                new UnavailableBilibiliSelectedVideoSummaryGateway().summarize(request).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.selectedBvids()).containsExactly("BV1xx411c7mD");
        assertThat(result.summary()).isNull();
        assertThat(result.boundary()).contains("明确选择");
        assertThat(result.nextStep()).contains("受控内容源");
    }

    @Test
    void selectionContractRejectsEmptyOrMalformedBvids() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BilibiliSelectedVideoSummaryRequest(List.of(), ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BilibiliSelectedVideoSummaryRequest(List.of("not-a-bvid"), ""));
    }
}
