package com.meguri.core.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningFusionAndAssemblyTest {
    @Test
    void ordinaryQuestionDoesNotEnableGraphButRelationshipQuestionDoes() {
        DefaultRetrievalPlanner planner = new DefaultRetrievalPlanner();

        assertThat(planner.plan(
                "Meguri \u662f\u4ec0\u4e48\uff1f",
                RetrievalMode.FAST, future()).graphEnabled())
                .isFalse();
        RetrievalPlan relationship = planner.plan(
                "Meguri \u548c Shizuki \u7684\u5173\u7cfb\u662f\u4ec0\u4e48\uff1f",
                RetrievalMode.FAST, future());
        assertThat(relationship.graphEnabled()).isTrue();
        assertThat(relationship.query().graphIntent())
                .isEqualTo(QueryRewriteResult.GraphIntent.RELATION);
        assertThat(relationship.graphMaxHops()).isBetween(1, 3);

        for (String keyword : List.of(
                "\u5173\u7cfb", "\u5173\u8054", "\u8fde\u63a5", "\u4f9d\u8d56",
                "\u5c5e\u4e8e", "\u5408\u4f5c", "\u8c01\u548c\u8c01")) {
            assertThat(planner.plan(
                    "Meguri " + keyword + " Shizuki",
                    RetrievalMode.FAST, future()).graphEnabled())
                    .as("Chinese relationship keyword %s", keyword)
                    .isTrue();
        }
    }

    @Test
    void rewriteExpressesRelationTimelineAndCallChainIntents() {
        DefaultRetrievalPlanner planner = new DefaultRetrievalPlanner();

        RetrievalPlan relation = planner.plan(
                "Meguri \u4f9d\u8d56 Shizuki", RetrievalMode.FAST, future());
        RetrievalPlan timeline = planner.plan(
                "Meguri \u548c Shizuki \u7684\u65f6\u95f4\u7ebf",
                RetrievalMode.FAST, future());
        RetrievalPlan callChain = planner.plan(
                "A \u5230 C \u7684\u8c03\u7528\u94fe", RetrievalMode.FAST, future());

        assertThat(relation.query().relationType()).isEqualTo("depends_on");
        assertThat(relation.query().graphIntent())
                .isEqualTo(QueryRewriteResult.GraphIntent.RELATION);
        assertThat(timeline.query().graphIntent())
                .isEqualTo(QueryRewriteResult.GraphIntent.TIMELINE);
        assertThat(timeline.query().relationType()).isEmpty();
        assertThat(callChain.query().graphIntent())
                .isEqualTo(QueryRewriteResult.GraphIntent.CALL_CHAIN);
        assertThat(callChain.query().relationType()).isEqualTo("calls");
    }

    @Test
    void gateRejectsWebInFastAndUnboundedPlans() {
        RetrievalGate gate = new RetrievalGate();
        RetrievalPlan fastWeb = new RetrievalPlan(
                RetrievalMode.FAST, QueryRewriteResult.unchanged("q"),
                Set.of(SourceType.WEB), Map.of(SourceType.WEB, 1),
                Map.of(SourceType.WEB, 1), false, 1, 1, future());

        assertThatThrownBy(() -> gate.validate(fastWeb))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAST");
        assertThatThrownBy(() -> gate.validate(new RetrievalPlan(
                RetrievalMode.SLOW, QueryRewriteResult.unchanged("q"),
                Set.of(SourceType.KNOWLEDGE),
                Map.of(SourceType.KNOWLEDGE, 33), Map.of(), true, 4, 64, future())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(gate.boundedBudgets(Map.of(SourceType.KNOWLEDGE, 10_000)))
                .containsEntry(SourceType.KNOWLEDGE, RetrievalGate.MAX_SOURCE_ITEMS);
    }

    @Test
    void weightedRrfPreservesOriginalRanksRatherThanLinearlyMixingScores() {
        WeightedRrfFusion fusion = new WeightedRrfFusion(
                10, Map.of(RankSignal.KEYWORD, 2.0, RankSignal.VECTOR, 1.0));
        RetrievalItem a = item(SourceType.LORE, "a", 0);
        RetrievalItem b = item(SourceType.LORE, "b", 0);

        List<RetrievalItem> result = fusion.fuse(Map.of(
                RankSignal.KEYWORD, List.of(a, b),
                RankSignal.VECTOR, List.of(b, a)));

        assertThat(result).extracting(RetrievalItem::sourceId).containsExactly("a", "b");
        assertThat(result.getFirst().rankTrace().ranks())
                .containsEntry(RankSignal.KEYWORD, 1)
                .containsEntry(RankSignal.VECTOR, 2);
        assertThat(result.getFirst().rankTrace().rrfScore())
                .isEqualTo(2.0 / 11.0 + 1.0 / 12.0);
    }

    @Test
    void assemblerHonorsSeatsAndCapsAndGraphCannotAddKnowledgeSeats() {
        RetrievalPlan plan = plan(
                Map.of(SourceType.LORE, 2, SourceType.KNOWLEDGE, 2),
                Map.of(SourceType.LORE, 1, SourceType.KNOWLEDGE, 1), 4);
        List<RetrievalItem> graphAndHybrid = List.of(
                scored(SourceType.KNOWLEDGE, "graph:1", 9),
                scored(SourceType.KNOWLEDGE, "hybrid:1", 8),
                scored(SourceType.KNOWLEDGE, "graph:2", 7));
        RetrievalBundle bundle = new BundleAssembler().assemble("trace", plan, List.of(
                RetrievalLaneResult.success(SourceType.KNOWLEDGE, "graph", graphAndHybrid),
                RetrievalLaneResult.success(SourceType.LORE, "lore", List.of(
                        scored(SourceType.LORE, "lore:1", 3),
                        scored(SourceType.LORE, "lore:2", 2)))));

        assertThat(bundle.items()).hasSize(4);
        assertThat(bundle.items()).filteredOn(i -> i.sourceType() == SourceType.KNOWLEDGE)
                .hasSize(2);
        assertThat(bundle.items()).filteredOn(i -> i.sourceType() == SourceType.LORE)
                .isNotEmpty();
    }

    private static RetrievalPlan plan(
            Map<SourceType, Integer> budgets, Map<SourceType, Integer> seats, int total) {
        return new RetrievalPlan(
                RetrievalMode.FAST, QueryRewriteResult.unchanged("q"),
                budgets.keySet(), budgets, seats, true, 3, total, future());
    }

    static RetrievalItem item(SourceType source, String id, double score) {
        return new RetrievalItem(source, id, id + " content", "cite:" + id, 0.8,
                new RankTrace(Map.of(), score), 3, Instant.EPOCH, null,
                List.of(), List.of(), "trace");
    }

    static RetrievalItem scored(SourceType source, String id, double score) {
        return item(source, id, score);
    }

    static Instant future() {
        return Instant.now().plusSeconds(10);
    }
}
