package com.meguri.core.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BillingDigestReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersOnlyAggregateDataFromSuccessfulSyncDigest() throws Exception {
        Path digest = temporaryDirectory.resolve("daily-billing-digest.json");
        Files.writeString(digest, """
                {"status":"success","target_date":"2026-07-20","generated_at":"2026-07-21T02:01:00+08:00",
                 "sync":{"imported_count":2,"duplicate_count":1,"skipped_count":0},
                 "analytics":{"base_currency":"CNY","income_total":1000,"expense_total":128.5,
                 "net_flow":871.5,"tx_count":4,"net_asset":3000,
                 "top_expense_categories":[{"category":"餐饮","amount":80}]}}
                """);

        BillingBriefing briefing = new BillingDigestReader(new ObjectMapper(), digest).latest();

        assertThat(briefing.status()).isEqualTo("ready");
        assertThat(briefing.briefing()).contains("收入 CNY 1000.00").contains("主要支出：餐饮 CNY 80.00");
        assertThat(briefing.syncImportedCount()).isEqualTo(2);
    }

    @Test
    void failsClosedWhenNoPostSyncDigestExists() {
        BillingBriefing briefing = new BillingDigestReader(new ObjectMapper(), temporaryDirectory.resolve("missing.json")).latest();
        assertThat(briefing.status()).isEqualTo("unavailable");
    }

    @Test
    void publishesOnlyExistingSameOriginReportArtifacts() throws Exception {
        Path digest = temporaryDirectory.resolve("daily-billing-digest.json");
        Files.writeString(digest, """
                {"status":"success","target_date":"2026-07-20","analytics":{
                 "income_total":0,"expense_total":0,"net_flow":0,"tx_count":0,"net_asset":0}}
                """);
        Path report = temporaryDirectory.resolve("reports/daily");
        Files.createDirectories(report);
        Files.writeString(report.resolve("billing-2026-07-20.md"), "# report");

        BillingBriefing briefing = new BillingDigestReader(new ObjectMapper(), digest, temporaryDirectory).latest();

        assertThat(briefing.artifacts()).containsExactly(new BillingArtifact("Markdown 日报", "/reports/daily/billing-2026-07-20.md"));
    }
}
