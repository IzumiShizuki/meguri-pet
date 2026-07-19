package com.meguri.core.rag;

import com.meguri.core.dto.RuntimeState;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;

/** Adapter around LangChain4j's ContentRetriever/EmbeddingStore retriever API. */
public final class LangChain4jRagProvider implements RagProvider {
    private final ContentRetriever retriever;

    public LangChain4jRagProvider(ContentRetriever retriever) {
        if (retriever == null) throw new IllegalArgumentException("ContentRetriever is required");
        this.retriever = retriever;
    }

    @Override
    public List<String> search(String query, RuntimeState state, int limit) {
        if (limit <= 0 || query == null || query.isBlank()) return List.of();
        List<Content> contents = retriever.retrieve(Query.from(query));
        if (contents == null || contents.isEmpty()) return List.of();
        return contents.stream()
                .filter(content -> content != null && content.textSegment() != null)
                .map(content -> content.textSegment().text())
                .filter(text -> text != null && !text.isBlank())
                .limit(limit)
                .toList();
    }

    public ContentRetriever retriever() {
        return retriever;
    }
}
