package com.meguri.core.capability;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Text and links only. No rendering, resource loads, XML entities, or instruction promotion. */
final class PublicWebDocument {
    private static final Set<String> OMIT = Set.of("script", "style", "noscript", "iframe",
            "object", "embed", "svg", "template", "textarea");
    static Map<String,Object> extract(URI source, String body, boolean plain, int maxChars, int redirects) throws Exception {
        Collector collector = new Collector(source, maxChars);
        if (plain) collector.text.appendRaw(body);
        else new ParserDelegator().parse(new StringReader(body), collector, true);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("url", source.toASCIIString());
        result.put("title", collector.title.value());
        result.put("links", List.copyOf(collector.links));
        result.put("text", collector.text.value());
        result.put("truncated", collector.text.truncated || collector.title.truncated || collector.linksTruncated);
        result.put("linksTruncated", collector.linksTruncated);
        result.put("redirects", redirects);
        result.put("trust", "UNTRUSTED_EXTERNAL");
        return Map.copyOf(result);
    }

    private static final class Collector extends HTMLEditorKit.ParserCallback {
        private final URI source;
        private final BoundedText text;
        private final BoundedText title = new BoundedText(256);
        private final List<Map<String,String>> links = new ArrayList<>();
        private final Map<String,Integer> omitted = new java.util.HashMap<>();
        private boolean inTitle, linksTruncated;
        private URI href;
        private BoundedText linkText = new BoundedText(256);
        Collector(URI source, int limit) { this.source = source; this.text = new BoundedText(limit); }
        @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
            checkCancellation();
            String name = tag.toString();
            if (OMIT.contains(name)) omitted.merge(name, 1, Integer::sum);
            if (!omitted.isEmpty()) return;
            if (tag == HTML.Tag.TITLE) inTitle = true;
            if (tag.isBlock()) text.append("\n");
            if (tag == HTML.Tag.A) {
                finishLink();
                Object target = attributes.getAttribute(HTML.Attribute.HREF);
                try {
                    if (target != null && target.toString().length() <= 2048)
                        href = PublicWebFetchPolicy.parse(source.resolve(target.toString()).toString());
                } catch (RuntimeException ignored) { href = null; }
                linkText = new BoundedText(256);
            }
        }
        @Override public void handleEndTag(HTML.Tag tag, int pos) {
            checkCancellation();
            String name = tag.toString();
            if (omitted.containsKey(name)) {
                omitted.compute(name, (key, count) -> count == 1 ? null : count - 1);
                return;
            }
            if (!omitted.isEmpty()) return;
            if (tag == HTML.Tag.TITLE) inTitle = false;
            if (tag == HTML.Tag.A) finishLink();
            if (tag.isBlock()) text.append("\n");
        }
        @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
            if ("true".equals(String.valueOf(attributes.getAttribute(HTML.Attribute.ENDTAG)))) handleEndTag(tag, pos);
            else if (OMIT.contains(tag.toString())) {
                // Unknown paired HTML5 containers arrive as simple tags.
                if (!Set.of("embed").contains(tag.toString())) omitted.merge(tag.toString(), 1, Integer::sum);
            } else if (omitted.isEmpty() && tag == HTML.Tag.BR) text.append("\n");
        }
        @Override public void handleText(char[] data, int pos) {
            checkCancellation();
            if (!omitted.isEmpty()) return;
            String value = new String(data);
            if (inTitle) title.append(value);
            else { text.append(value); text.append(" "); }
            if (href != null) linkText.append(value);
        }
        private void finishLink() {
            if (href == null) return;
            if (links.size() < 32) {
                links.add(Map.of("text", linkText.value(), "url", href.toASCIIString()));
                text.append(" (" + href.toASCIIString() + ") ");
            } else linksTruncated = true;
            href = null;
        }
        private static void checkCancellation() {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        }
    }

    private static final class BoundedText {
        private final int limit;
        private final StringBuilder value = new StringBuilder();
        private int count;
        private boolean truncated, pendingSpace;
        BoundedText(int limit) { this.limit = limit; }
        void append(String input) {
            for (int i = 0; i < input.length();) {
                int point = input.codePointAt(i);
                i += Character.charCount(point);
                if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                    if (count > 0) pendingSpace = true;
                    continue;
                }
                if (pendingSpace && count < limit) { value.append(' '); count++; }
                pendingSpace = false;
                if (count < limit) { value.appendCodePoint(point); count++; }
                else { truncated = true; return; }
            }
        }
        void appendRaw(String input) {
            int size = input.codePointCount(0, input.length());
            int available = Math.max(0, limit - count);
            int taken = Math.min(size, available);
            value.append(input, 0, input.offsetByCodePoints(0, taken));
            count += taken;
            if (size > taken) truncated = true;
        }
        String value() { return value.toString().strip(); }
    }
}
