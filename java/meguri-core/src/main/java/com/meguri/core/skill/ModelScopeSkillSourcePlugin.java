package com.meguri.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ModelScopeSkillSourcePlugin implements SkillSourcePlugin {
    public static final String SOURCE_ID = "modelscope";
    private static final Pattern PUBLIC_SKILL_ID = Pattern.compile(
            "^@?[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private final Transport transport;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final URI bridgeUri;
    private final String bridgeToken;
    private final Duration timeout;

    public ModelScopeSkillSourcePlugin(
            HttpClient client, ObjectMapper mapper, URI baseUri, URI bridgeUri,
            String bridgeToken, Duration timeout) {
        this(request -> {
            HttpResponse<String> response = Objects.requireNonNull(client, "client").send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new TransportResponse(response.statusCode(), response.body());
        }, mapper, baseUri, bridgeUri, bridgeToken, timeout);
    }

    ModelScopeSkillSourcePlugin(
            Transport transport, ObjectMapper mapper, URI baseUri, URI bridgeUri,
            String bridgeToken, Duration timeout) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUri = exactModelScope(baseUri);
        this.bridgeUri = loopbackBridge(bridgeUri);
        this.bridgeToken = bridgeToken == null ? "" : bridgeToken;
        this.timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    @Override public String id() { return SOURCE_ID; }

    @Override public SearchPage search(String query, int page, int pageSize) {
        JsonNode data = get("/openapi/v1/skills?search=" + encode(query == null ? "" : query)
                + "&page_number=" + page + "&page_size=" + pageSize).path("data");
        ArrayList<Summary> values = new ArrayList<>();
        data.path("skills").forEach(node -> { if (!node.path("private").asBoolean(false)) values.add(summary(node)); });
        return new SearchPage(values, data.path("page_number").asInt(page),
                data.path("page_size").asInt(pageSize), data.path("total").asLong(values.size()));
    }

    @Override public Detail detail(String externalId) {
        JsonNode root = get("/openapi/v1/skills/" + pathId(validExternalId(externalId)));
        JsonNode node = root.path("data");
        if (node.has("skill")) node = node.path("skill");
        if (node.path("private").asBoolean(false)) throw failure("MODELSCOPE_PRIVATE_SKILL", false);
        Summary summary = summary(node);
        SkillRequirement requirements = new SkillRequirement(strings(node.path("requires").path("tools")),
                strings(node.path("requires").path("env")));
        return new Detail(summary, requirements, Map.of(
                "developer", node.path("developer").asText(""),
                "category", node.path("category").asText(""),
                "source_url", node.path("source_url").asText(""),
                "downloads", node.path("downloads").asLong(0)));
    }

    @Override public FetchResult fetch(String externalId) {
        try {
            String safeExternalId = validExternalId(externalId);
            String body = mapper.writeValueAsString(Map.of("skill_id", safeExternalId));
            HttpRequest request = HttpRequest.newBuilder(bridgeUri.resolve("/internal/skills/modelscope/fetch"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("X-Meguri-Internal-Token", bridgeToken).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode json = send(request, "MODELSCOPE_BRIDGE_UNAVAILABLE");
            Path staging = Path.of(json.path("staging_path").asText(""));
            if (!staging.isAbsolute()) throw failure("MODELSCOPE_BRIDGE_INVALID_RESPONSE", false);
            String fetchId = json.path("fetch_id").asText("");
            if (!fetchId.isBlank() && !fetchId.matches("[0-9a-f]{32}")) {
                throw failure("MODELSCOPE_BRIDGE_INVALID_RESPONSE", false);
            }
            return new FetchResult(safeExternalId, json.path("revision").asText(""), staging,
                    fetchId.isBlank() ? null : fetchId);
        } catch (SkillSourcePlugin.SourceException error) { throw error; }
        catch (Exception error) { throw failure("MODELSCOPE_BRIDGE_UNAVAILABLE", true); }
    }

    @Override public void release(FetchResult result) {
        if (result == null || result.fetchId() == null || result.fetchId().isBlank()) return;
        try {
            String body = mapper.writeValueAsString(Map.of("fetch_id", result.fetchId()));
            HttpRequest request = HttpRequest.newBuilder(bridgeUri.resolve("/internal/skills/modelscope/release"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("X-Meguri-Internal-Token", bridgeToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            send(request, "MODELSCOPE_BRIDGE_UNAVAILABLE");
        } catch (RuntimeException | IOException ignored) {
            // Staging release is best-effort and must never roll back an imported immutable revision.
        }
    }

    @Override public UpdateStatus checkUpdate(String externalId, String currentRevision) {
        Detail detail = detail(externalId);
        String latest = detail.summary().revision();
        return new UpdateStatus(!Objects.equals(latest, currentRevision), currentRevision, latest);
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        return send(request, "MODELSCOPE_SOURCE_UNAVAILABLE");
    }
    private JsonNode send(HttpRequest request, String transportCode) {
        try {
            TransportResponse response = transport.send(request);
            if (response.statusCode() == 404) throw failure("MODELSCOPE_SKILL_NOT_FOUND", false);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw failure(transportCode, response.statusCode() >= 500);
            JsonNode json = mapper.readTree(response.body());
            if (json.has("success") && !json.path("success").asBoolean()) throw failure(transportCode, false);
            return json;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw failure(transportCode, true); }
        catch (IOException error) { throw failure(transportCode, true); }
    }
    private Summary summary(JsonNode node) {
        String externalId;
        try {
            externalId = validExternalId(node.path("id").asText());
        } catch (SourceException invalid) {
            throw failure("MODELSCOPE_RESPONSE_INVALID", false);
        }
        Instant updated = instant(node.path("file_last_modified").asText(
                node.path("last_modified").asText("")));
        return new Summary(externalId, node.path("display_name").asText(externalId),
                node.path("description").asText(""), blankToNull(node.path("license").asText("")),
                strings(node.path("tags")), revision(node), updated);
    }
    private static String revision(JsonNode node) {
        String file = node.path("file_last_modified").asText("");
        return file.isBlank() ? node.path("last_modified").asText("") : file;
    }
    private static List<String> strings(JsonNode node) {
        ArrayList<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText()); });
        return List.copyOf(values);
    }
    private static Instant instant(String value) { try { return value == null || value.isBlank() ? null : Instant.parse(value); } catch (Exception ignored) { return null; } }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String validExternalId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!PUBLIC_SKILL_ID.matcher(normalized).matches()) {
            throw failure("MODELSCOPE_SKILL_ID_INVALID", false);
        }
        return normalized;
    }
    private static String pathId(String value) { return encode(value).replace("%2F", "/"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static SourceException failure(String code, boolean retryable) { return new SourceException(code, "ModelScope Skill operation failed", retryable); }
    private static URI exactModelScope(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !"modelscope.cn".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("ModelScope base URL must be https://modelscope.cn");
        }
        return URI.create("https://modelscope.cn");
    }
    private static URI loopbackBridge(URI uri) {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !Set.of("127.0.0.1", "localhost", "::1").contains(uri.getHost())) {
            throw new IllegalArgumentException("ModelScope bridge must be loopback");
        }
        return uri;
    }

    @FunctionalInterface
    interface Transport {
        TransportResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, String body) {
        TransportResponse {
            body = body == null ? "" : body;
        }
    }
}
