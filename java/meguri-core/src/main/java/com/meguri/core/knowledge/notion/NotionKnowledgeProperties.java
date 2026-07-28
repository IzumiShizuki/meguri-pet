package com.meguri.core.knowledge.notion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties("meguri.knowledge.notion")
public final class NotionKnowledgeProperties {
    private boolean enabled;
    private String baseUrl = "https://api.notion.com";
    private String token = "";
    private String tokenFile = "";
    private String sourceId = "notion";
    private String tenantId = "meguri-local";
    private Set<String> principals = new LinkedHashSet<>(Set.of("user:local"));
    private Set<String> allowlist = new LinkedHashSet<>();
    private Duration timeout = Duration.ofSeconds(30);
    private int maxDepth = 16;
    private int maxBlocks = 10_000;
    private boolean manualSyncEnabled;
    private String managementToken = "";
    private String managementTokenFile = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenFile() {
        return tokenFile;
    }

    public void setTokenFile(String tokenFile) {
        this.tokenFile = tokenFile;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Set<String> getPrincipals() {
        return Set.copyOf(principals);
    }

    public void setPrincipals(Set<String> principals) {
        this.principals = copy(principals);
    }

    public Set<String> getAllowlist() {
        return Set.copyOf(allowlist);
    }

    public void setAllowlist(Set<String> allowlist) {
        this.allowlist = copy(allowlist);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxBlocks() {
        return maxBlocks;
    }

    public void setMaxBlocks(int maxBlocks) {
        this.maxBlocks = maxBlocks;
    }

    public boolean isManualSyncEnabled() {
        return manualSyncEnabled;
    }

    public void setManualSyncEnabled(boolean manualSyncEnabled) {
        this.manualSyncEnabled = manualSyncEnabled;
    }

    public String getManagementToken() {
        return managementToken;
    }

    public void setManagementToken(String managementToken) {
        this.managementToken = managementToken;
    }

    public String getManagementTokenFile() {
        return managementTokenFile;
    }

    public void setManagementTokenFile(String managementTokenFile) {
        this.managementTokenFile = managementTokenFile;
    }

    private static Set<String> copy(Set<String> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
}
