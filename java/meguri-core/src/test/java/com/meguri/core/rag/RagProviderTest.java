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
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagProviderTest {
    @TempDir Path temp;

    private RuntimeState state() {
        return new RuntimeState("airi", Mode.PRIVATE, Relationship.LOVER, "03",
                "2026-07-18T10:00:00+08:00", false, false, false, List.of(ExpressionTag.NEUTRAL));
    }

    @Test
    void lexicalRetrieverRanksTermsAndRelationship() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), """
                {"build_id":"test-build","text_zh":"普通工作记录"}
                {"build_id":"test-build","text_zh":"恋人蓝色约定","relationship_stage":"lover"}
                {"build_id":"test-build","text_zh":"无关内容","relationship_stage":"sibling"}
                """);
        CanonicalRagRetriever retriever = new CanonicalRagRetriever(temp, new ObjectMapper(), "test-build");
        assertEquals(List.of("恋人蓝色约定", "普通工作记录"), retriever.search("蓝色", state(), 2));
    }

    @Test
    void buildMismatchFailsClosed() throws Exception {
        Path rag = temp.resolve("exports/rag");
        Files.createDirectories(rag);
        Files.writeString(rag.resolve("chunks_train.jsonl"), "{\"build_id\":\"wrong\",\"text\":\"x\"}\n");
        assertThrows(IllegalStateException.class, () -> new CanonicalRagRetriever(temp, new ObjectMapper(), "expected"));
    }

    @Test
    void langChainRetrieverAdapterUsesFrameworkContentRetriever() {
        ContentRetriever delegate = query -> List.of(Content.from("framework result"), Content.from("second"));
        LangChain4jRagProvider provider = new LangChain4jRagProvider(delegate);
        assertEquals(List.of("framework result"), provider.search("hello", state(), 1));
    }
}
