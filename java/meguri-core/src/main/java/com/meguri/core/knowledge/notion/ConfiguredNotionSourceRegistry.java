package com.meguri.core.knowledge.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.knowledge.KnowledgeAcl;
import com.meguri.core.knowledge.NotionApiCredentials;
import com.meguri.core.knowledge.NotionApiSourceRegistry;
import com.meguri.core.knowledge.NotionHttpPort;
import com.meguri.core.knowledge.NotionSyncLimits;
import com.meguri.core.knowledge.SourcePage;
import com.meguri.core.knowledge.SourceRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Configuration-aware source boundary. Credential resolution is deliberately
 * delayed until scan time so missing local secrets never prevent application startup.
 */
public final class ConfiguredNotionSourceRegistry implements SourceRegistry {
    private final NotionKnowledgeProperties properties;
    private final NotionHttpPort http;
    private final ObjectMapper mapper;
    private Map<String, SourcePage> snapshot = Map.of();
    private String snapshotConfiguration = "";

    public ConfiguredNotionSourceRegistry(
            NotionKnowledgeProperties properties,
            NotionHttpPort http,
            ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String sourceId() {
        return properties.getSourceId();
    }

    @Override
    public synchronized List<SourcePage> scan() {
        requireReady();
        KnowledgeAcl acl = new KnowledgeAcl(
                properties.getTenantId(), properties.getPrincipals());
        NotionSyncLimits limits = new NotionSyncLimits(
                properties.getMaxDepth(), properties.getMaxBlocks(), properties.getTimeout());
        String configuration = snapshotConfiguration();
        Map<String, SourcePage> reusable = configuration.equals(snapshotConfiguration)
                ? snapshot : Map.of();
        List<SourcePage> pages = new NotionApiSourceRegistry(
                properties.getSourceId(), acl, properties.getAllowlist(),
                new NotionApiCredentials(resolveToken()), http, mapper, limits)
                .scan(reusable);
        LinkedHashMap<String, SourcePage> next = new LinkedHashMap<>();
        pages.forEach(page -> next.put(page.pageId(), page));
        snapshot = Map.copyOf(next);
        snapshotConfiguration = configuration;
        return pages;
    }

    private String snapshotConfiguration() {
        return String.join("\n",
                properties.getBaseUrl(),
                properties.getSourceId(),
                properties.getTenantId(),
                credentialFingerprint(resolveToken()),
                new TreeSet<>(properties.getPrincipals()).toString(),
                new TreeSet<>(properties.getAllowlist()).toString(),
                properties.getTimeout().toString(),
                Integer.toString(properties.getMaxDepth()),
                Integer.toString(properties.getMaxBlocks()));
    }

    private static String credentialFingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public void requireReady() {
        if (!properties.isEnabled()) {
            throw new NotionSyncUnavailableException("Notion knowledge synchronization is disabled");
        }
        if (properties.getAllowlist().isEmpty()) {
            throw new NotionSyncUnavailableException("Notion knowledge allowlist is empty");
        }
        resolveToken();
    }

    private String resolveToken() {
        String direct = properties.getToken();
        if (direct != null && !direct.isBlank()) return direct.strip();
        String tokenFile = properties.getTokenFile();
        if (tokenFile == null || tokenFile.isBlank()) {
            throw new NotionSyncUnavailableException("Notion knowledge token is not configured");
        }
        try {
            String value = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
            if (value.isBlank()) {
                throw new NotionSyncUnavailableException("Notion knowledge token file is empty");
            }
            return value;
        } catch (NotionSyncUnavailableException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new NotionSyncUnavailableException("Notion knowledge token file is unavailable");
        }
    }
}
