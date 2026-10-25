package com.meguri.core.daily;

import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** Reads only Juya's public feed; article markup is reference data. */
public final class JuyaRssReader {
    public static final String FEED_URL = "https://daily.juya.uk/rss.xml";
    static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final Pattern DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public Article fetch() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(FEED_URL))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Meguri-AI-News/1.0")
                .header("Accept", "application/rss+xml, application/xml").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("RSS unavailable");
            return parse(body.readNBytes(MAX_FEED_BYTES + 1));
        }
    }

    static Article parse(byte[] bytes) throws Exception {
        if (bytes.length > MAX_FEED_BYTES) throw new IllegalArgumentException("RSS too large");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        // Avoid dumping external XML fragments to stderr on parse failures.
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException error)
                    throws org.xml.sax.SAXException { throw error; }
        });
        var items = builder.parse(new ByteArrayInputStream(bytes)).getElementsByTagName("item");
        if (items.getLength() == 0) throw new IllegalArgumentException("RSS has no items");
        Element item = (Element) items.item(0);
        String title = text(item, "title");
        var match = DATE.matcher(title);
        LocalDate date = match.find() ? LocalDate.parse(match.group(1))
                : ZonedDateTime.parse(text(item, "pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        URI link = URI.create(text(item, "link"));
        if (!"https".equals(link.getScheme()) || link.getUserInfo() != null
                || !("daily.juya.uk".equals(link.getHost()) || "imjuya.github.io".equals(link.getHost()))) {
            throw new IllegalArgumentException("RSS article source is invalid");
        }
        var encoded = item.getElementsByTagNameNS("http://purl.org/rss/1.0/modules/content/", "encoded");
        String html = encoded.getLength() > 0 ? encoded.item(0).getTextContent() : text(item, "description");
        String content = HtmlUtils.htmlUnescape(html
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)</(?:p|div|li|h[1-6])>|<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", ""))
                .replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (content.length() < 50) throw new IllegalArgumentException("RSS article is too short");
        if (content.length() > 18000) content = content.substring(0, 18000) + "\n[正文已按长度截断]";
        return new Article(date, title, link.toString(), content);
    }

    private static String text(Element element, String name) {
        var values = element.getElementsByTagName(name);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().trim();
    }

    public record Article(LocalDate date, String title, String url, String content) {}
}
