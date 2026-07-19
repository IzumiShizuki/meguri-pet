package com.meguri.core.websearch;

/** One bounded, citation-friendly web search result. */
public record WebSearchResult(String title, String url, String snippet) {
    public WebSearchResult {
        title = title == null ? "" : title.trim();
        url = url == null ? "" : url.trim();
        snippet = snippet == null ? "" : snippet.trim();
        if (title.isBlank() && url.isBlank() && snippet.isBlank()) {
            throw new IllegalArgumentException("web search result must contain text");
        }
    }

    public String asContextLine() {
        return "title=" + title + "\nurl=" + url + "\nsnippet=" + snippet;
    }
}
