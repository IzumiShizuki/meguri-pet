package com.meguri.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the aggregate digest only; Meguri never reads Qianji auth files or raw transactions. */
@Service
public final class BillingDigestReader {
    private final ObjectMapper mapper;
    private final Path digestFile;
    private final Path projectRoot;

    @Autowired
    public BillingDigestReader(ObjectMapper mapper,
                               @Value("${meguri.billing-digest.file:D:/program/shizuki-site/data/qianji-sync/daily-billing-digest.json}") String digestFile,
                               @Value("${meguri.billing-digest.project-root:D:/program/meguri-pet}") String projectRoot) {
        this(mapper, Path.of(digestFile), Path.of(projectRoot));
    }

    BillingDigestReader(ObjectMapper mapper, Path digestFile) {
        this(mapper, digestFile, Path.of("D:/program/meguri-pet"));
    }

    BillingDigestReader(ObjectMapper mapper, Path digestFile, Path projectRoot) {
        this.mapper = mapper;
        this.digestFile = digestFile;
        this.projectRoot = projectRoot;
    }

    public BillingBriefing latest() {
        if (!Files.isRegularFile(digestFile)) {
            return BillingBriefing.unavailable("尚未找到同步成功后的账单日报。");
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(digestFile));
            if (!"success".equals(root.path("status").asText())) {
                return BillingBriefing.unavailable("最近一次账单日报未成功生成。");
            }
            JsonNode analytics = root.path("analytics");
            JsonNode sync = root.path("sync");
            String currency = analytics.path("base_currency").asText("CNY");
            String targetDate = root.path("target_date").asText("昨日");
            String briefing = render(targetDate, currency,
                    decimal(analytics, "income_total"), decimal(analytics, "expense_total"),
                    decimal(analytics, "net_flow"), analytics.path("tx_count").asInt(),
                    decimal(analytics, "net_asset"), analytics.path("top_expense_categories"));
            return new BillingBriefing("ready", root.path("target_date").asText(),
                    root.path("generated_at").asText(), sync.path("imported_count").asInt(),
                    sync.path("duplicate_count").asInt(), sync.path("skipped_count").asInt(), briefing,
                    artifacts(targetDate), null);
        } catch (IOException | RuntimeException error) {
            return BillingBriefing.unavailable("账单日报无法读取，请先检查钱迹同步是否成功。");
        }
    }

    private List<BillingArtifact> artifacts(String date) {
        List<BillingArtifact> links = new ArrayList<>();
        addIfPresent(links, projectRoot.resolve("reports/daily/billing-" + date + ".md"),
                "Markdown 日报", "/reports/daily/billing-" + date + ".md");
        addIfPresent(links, projectRoot.resolve("output/pdf/billing-" + date + ".pdf"),
                "PDF 日报", "/output/pdf/billing-" + date + ".pdf");
        addIfPresent(links, projectRoot.resolve("reports/daily/billing-" + date + ".jpg"),
                "JPG 预览", "/reports/daily/billing-" + date + ".jpg");
        return List.copyOf(links);
    }

    private static void addIfPresent(List<BillingArtifact> links, Path file, String label, String href) {
        if (Files.isRegularFile(file)) links.add(new BillingArtifact(label, href));
    }

    private static BigDecimal decimal(JsonNode parent, String field) {
        return parent.path(field).decimalValue().setScale(2, RoundingMode.HALF_UP);
    }

    private static String render(String date, String currency, BigDecimal income, BigDecimal expense,
                                 BigDecimal net, int count, BigDecimal netAsset, JsonNode categories) {
        List<String> top = new ArrayList<>();
        if (categories.isArray()) {
            categories.forEach(item -> top.add(item.path("category").asText("未分类") + " "
                    + currency + " " + decimal(item, "amount")));
        }
        String categoryText = top.isEmpty() ? "暂无分类统计" : "主要支出：" + String.join("、", top);
        return "%s账单已在钱迹同步后汇总：收入 %s %s，支出 %s %s，净流 %s %s，共 %d 笔；当前净资产 %s %s。%s".formatted(
                date, currency, income, currency, expense, currency, net, count, currency, netAsset, categoryText);
    }
}
