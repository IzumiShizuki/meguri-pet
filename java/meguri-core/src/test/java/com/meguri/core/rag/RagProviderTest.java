package com.meguri.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagProviderTest {
    @TempDir Path temp;

    private RuntimeState state() {
        return state(Relationship.LOVER);
    }

    private RuntimeState state(Relationship relationship) {
        return new RuntimeState("airi", Mode.PRIVATE, relationship, "03",
                "2026-07-18T10:00:00+08:00", false, false, false, List.of(ExpressionTag.NEUTRAL));
    }

    @Test
    void hybridRetrieverHardFiltersRelationshipAndRejectsWeakFallbacks() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), """
                {"build_id":"test-build","chunk_id":"l1","scene_id":"lover-a","text_zh":"恋人蓝色约定","text_jp":"恋人の青い約束","relationship_stage":"lover"}
                {"build_id":"test-build","chunk_id":"s1","scene_id":"sibling-a","text_zh":"兄妹蓝色约定","text_jp":"兄妹の青い約束","relationship_stage":"sibling"}
                {"build_id":"test-build","chunk_id":"l2","scene_id":"lover-b","text_zh":"普通工作记录","text_jp":"普通の仕事記録","relationship_stage":"lover"}
                """);
        CanonicalRagRetriever retriever = new CanonicalRagRetriever(temp, new ObjectMapper(), "test-build");
        assertEquals(List.of("恋人蓝色约定"), retriever.search("蓝色", state(), 3));
        assertEquals(List.of("兄妹蓝色约定"), retriever.search("蓝色", state(Relationship.SIBLING), 3));
        assertTrue(retriever.search("量子电动力学", state(), 3).isEmpty());
        assertTrue(retriever.search("喜欢什么颜色", state(), 3).isEmpty());
    }

    @Test
    void queryLanguageSelectsTheMatchingCanonicalText() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), """
                {"build_id":"test-build","chunk_id":"l1","scene_id":"lover-a","text_zh":"爱莉: 蓝色的约定","text_jp":"メグリ: 青い約束","relationship_stage":"lover"}
                """);
        CanonicalRagRetriever retriever = new CanonicalRagRetriever(temp, new ObjectMapper(), "test-build");

        assertEquals(List.of("爱莉: 蓝色的约定"), retriever.search("蓝色约定", state(), 1));
        assertEquals(List.of("メグリ: 青い約束"), retriever.search("青い約束", state(), 1));
    }

    @Test
    void reviewedAliasesBridgeEverydayParaphrasesWithoutOpeningUnrelatedFallbacks() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), """
                {"build_id":"test-build","chunk_id":"l1","scene_id":"lover-a","text_zh":"爱莉: 今天也辛苦了\\n爱莉: 我刚刚买了新的发卡","text_jp":"メグリ: 今日もお疲れ様でした\\nメグリ: 新しい髪飾りを買いました","relationship_stage":"lover"}
                """);
        Path aliases = temp.resolve("aliases.json");
        Files.writeString(aliases, """
                {"version":"test","concepts":[
                  {"id":"fatigue","zh":["累","辛苦"],"ja":["疲れ","お疲れ"]}
                ]}
                """);
        CanonicalRagRetriever retriever = new CanonicalRagRetriever(
                temp, new ObjectMapper(), "test-build", aliases);

        assertEquals(List.of("爱莉: 今天也辛苦了"), retriever.search("今天工作好累", state(), 1));
        assertEquals(List.of("メグリ: 今日もお疲れ様でした"), retriever.search("今日は疲れた", state(), 1));
        assertTrue(retriever.search("帮我写一段代码", state(), 3).isEmpty());
    }

    @Test
    void resultsAreDiversifiedBySceneInsteadOfFillingTopKWithOnePlot() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), """
                {"build_id":"test-build","chunk_id":"l1","scene_id":"same","text_zh":"蓝色约定之一","text_jp":"青い約束その一","relationship_stage":"lover"}
                {"build_id":"test-build","chunk_id":"l2","scene_id":"same","text_zh":"蓝色约定之二","text_jp":"青い約束その二","relationship_stage":"lover"}
                {"build_id":"test-build","chunk_id":"l3","scene_id":"other","text_zh":"蓝色信物","text_jp":"青い記念品","relationship_stage":"lover"}
                """);
        CanonicalRagRetriever retriever = new CanonicalRagRetriever(temp, new ObjectMapper(), "test-build");

        assertEquals(2, retriever.search("蓝色", state(), 3).size());
    }

    @Test
    void buildMismatchFailsClosed() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), "{\"build_id\":\"wrong\",\"text\":\"x\"}\n");
        assertThrows(IllegalStateException.class, () -> new CanonicalRagRetriever(temp, new ObjectMapper(), "expected"));
    }

    @Test
    void missingBuildIdAlsoFailsClosed() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), "{\"text\":\"unversioned\"}\n");
        assertThrows(IllegalStateException.class,
                () -> new CanonicalRagRetriever(temp, new ObjectMapper(), "expected"));
    }

    @Test
    void langChainRetrieverAdapterUsesFrameworkContentRetriever() {
        ContentRetriever delegate = query -> List.of(Content.from("framework result"), Content.from("second"));
        LangChain4jRagProvider provider = new LangChain4jRagProvider(delegate);
        assertEquals(List.of("framework result"), provider.search("hello", state(), 1));
    }
}
