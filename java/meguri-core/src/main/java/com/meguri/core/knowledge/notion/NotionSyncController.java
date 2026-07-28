package com.meguri.core.knowledge.notion;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/knowledge")
public final class NotionSyncController {
    private final NotionSyncCoordinator coordinator;
    private final NotionKnowledgeProperties properties;

    public NotionSyncController(
            NotionSyncCoordinator coordinator,
            NotionKnowledgeProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @PostMapping("/notion:sync")
    public NotionSyncReport synchronize(
            @RequestHeader(
                    value = "X-Meguri-Admin-Token",
                    required = false) String presentedToken) {
        authorize(presentedToken);
        return coordinator.synchronizeNow();
    }

    private void authorize(String presentedToken) {
        if (!properties.isManualSyncEnabled()) {
            throw new NotionSyncAccessDeniedException(
                    HttpStatus.NOT_FOUND, "Notion manual sync endpoint is disabled");
        }
        String expected = managementToken();
        if (expected == null) {
            throw new NotionSyncUnavailableException(
                    "Notion manual sync management token is not configured");
        }
        boolean matches = presentedToken != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new NotionSyncAccessDeniedException(
                    HttpStatus.FORBIDDEN, "Notion manual sync access denied");
        }
    }

    private String managementToken() {
        String direct = properties.getManagementToken();
        if (direct != null && !direct.isBlank()) return direct.strip();
        String file = properties.getManagementTokenFile();
        if (file == null || file.isBlank()) return null;
        try {
            String value = Files.readString(
                    Path.of(file), StandardCharsets.UTF_8).strip();
            return value.isBlank() ? null : value;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    @ExceptionHandler(NotionSyncUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(NotionSyncUnavailableException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "NOTION_SYNC_UNAVAILABLE", "message", error.getMessage()));
    }

    @ExceptionHandler(NotionSyncAccessDeniedException.class)
    ResponseEntity<Map<String, String>> accessDenied(
            NotionSyncAccessDeniedException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of(
                        "code", error.status() == HttpStatus.NOT_FOUND
                                ? "NOT_FOUND" : "ACCESS_DENIED",
                        "message", error.getMessage()));
    }
}
