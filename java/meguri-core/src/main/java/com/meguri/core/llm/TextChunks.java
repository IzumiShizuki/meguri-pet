package com.meguri.core.llm;

import java.util.ArrayList;
import java.util.List;

final class TextChunks {
    private TextChunks() {}

    static List<String> of(String text, int size) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        int actualSize = Math.max(1, size);
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + actualSize);
            if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            if (end == start) end = Math.min(text.length(), start + 2);
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }
}
