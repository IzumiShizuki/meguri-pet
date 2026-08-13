package com.meguri.core.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Turn-scoped progressive disclosure ledger. No live catalog lookup occurs after freeze. */
public final class SkillDisclosureService {
    private final SkillManagementService management;
    private final Map<String, Ledger> ledgers = new ConcurrentHashMap<>();

    public SkillDisclosureService(SkillManagementService management) { this.management = management; }

    public void freeze(FrozenSkillSnapshot snapshot) { ledgers.put(snapshot.turnId(), new Ledger(snapshot)); }
    public void release(String turnId) {
        if (turnId != null) ledgers.remove(turnId);
    }
    public FrozenSkillSnapshot snapshot(String turnId) { return require(turnId).snapshot(); }

    public Map<String, Object> search(String turnId, String query) {
        Ledger ledger = require(turnId);
        String normalized = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        List<Map<String, Object>> items = ledger.snapshot.candidates().stream()
                .filter(value -> normalized.isBlank() || (value.name() + " " + value.description()
                        + " " + String.join(" ", value.tags())).toLowerCase(java.util.Locale.ROOT).contains(normalized))
                .map(value -> Map.<String, Object>of(
                        "skill_id", value.skillId(), "name", value.name(), "description", value.description(),
                        "revision", value.revision(), "digest", value.digest(), "score", value.score()))
                .toList();
        return Map.of("items", items, "frozen", true, "trust", "UNTRUSTED_EXTERNAL");
    }

    public Map<String, Object> view(String turnId, String skillId, String relativePath) {
        Ledger ledger = require(turnId);
        return ledger.view(skillId, relativePath == null || relativePath.isBlank() ? "SKILL.md" : relativePath);
    }

    private Ledger require(String turnId) {
        Ledger ledger = ledgers.get(turnId);
        if (ledger == null) throw new SkillManagementService.SkillException(
                "SKILL_SNAPSHOT_NOT_FROZEN", "Turn has no frozen Skill snapshot");
        return ledger;
    }

    private final class Ledger {
        private FrozenSkillSnapshot snapshot;
        private Ledger(FrozenSkillSnapshot snapshot) { this.snapshot = snapshot; }

        private synchronized Map<String, Object> view(String skillId, String relativePath) {
            FrozenSkillSnapshot.Candidate candidate = snapshot.candidates().stream()
                    .filter(v -> v.skillId().equals(skillId)).findFirst()
                    .orElseThrow(() -> new SkillManagementService.SkillException(
                            "SKILL_NOT_IN_FROZEN_SNAPSHOT", "Skill is not a frozen candidate"));
            String path = normalizeRelative(relativePath);
            boolean root = "SKILL.md".equals(path);
            LinkedHashSet<String> viewedSkills = new LinkedHashSet<>(snapshot.viewedSkillIds());
            LinkedHashSet<String> viewedReferences = new LinkedHashSet<>(snapshot.viewedReferences());
            String referenceKey = skillId + ":" + path;
            if (root && !viewedSkills.contains(skillId) && snapshot.remainingSkillViews() == 0) {
                throw limit("SKILL_VIEW_LIMIT_EXCEEDED");
            }
            if (!root && !viewedSkills.contains(skillId)) {
                throw new SkillManagementService.SkillException(
                        "SKILL_ROOT_VIEW_REQUIRED", "SKILL.md must be viewed before a reference file");
            }
            if (!root && !viewedReferences.contains(referenceKey) && snapshot.remainingReferenceViews() == 0) {
                throw limit("SKILL_REFERENCE_LIMIT_EXCEEDED");
            }
            Path file = management.packageFile(candidate.digest(), path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                throw new SkillManagementService.SkillException("SKILL_FILE_NOT_FOUND", "Skill text file is unavailable");
            }
            String text;
            try {
                if (Files.size(file) > SkillPackageValidator.MAX_FILE_BYTES) throw limit("SKILL_FILE_TOO_LARGE");
                byte[] bytes = Files.readAllBytes(file);
                if (!isText(bytes)) throw new SkillManagementService.SkillException(
                        "SKILL_FILE_NOT_TEXT", "Skill file is not safe text");
                text = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new SkillManagementService.SkillException("SKILL_FILE_UNREADABLE", "Skill file could not be read");
            }
            int estimated = tokenEstimate(text);
            int allowed = Math.min(estimated, snapshot.remainingTokens());
            boolean truncated = allowed < estimated;
            String bounded = truncateToTokens(text, allowed);
            if (root) viewedSkills.add(skillId); else viewedReferences.add(referenceKey);
            snapshot = new FrozenSkillSnapshot(snapshot.turnId(), snapshot.capabilitySnapshotId(), snapshot.candidates(),
                    viewedSkills, viewedReferences, snapshot.remainingTokens() - allowed,
                    snapshot.remainingSkillViews() - (root && !snapshot.viewedSkillIds().contains(skillId) ? 1 : 0),
                    snapshot.remainingReferenceViews() - (!root && !snapshot.viewedReferences().contains(referenceKey) ? 1 : 0),
                    snapshot.frozenAt());
            return Map.of(
                    "skill_id", skillId, "revision", candidate.revision(), "digest", candidate.digest(),
                    "path", path, "content", bounded, "tokens_used", allowed,
                    "remaining_tokens", snapshot.remainingTokens(), "truncated", truncated,
                    "trust", "USER_DATA/EXTERNAL_SKILL", "executable", false);
        }

        private synchronized FrozenSkillSnapshot snapshot() { return snapshot; }
    }

    private static String normalizeRelative(String value) {
        String portable = value.replace('\\', '/');
        if (portable.indexOf(':') >= 0 || java.util.Arrays.stream(portable.split("/", -1))
                .anyMatch(".."::equals)) {
            throw new SkillManagementService.SkillException(
                    "SKILL_PATH_INVALID", "path traversal is not allowed");
        }
        final Path path;
        try {
            path = Path.of(portable);
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new SkillManagementService.SkillException("SKILL_PATH_INVALID", "Skill path is invalid");
        }
        if (path.isAbsolute()) throw new SkillManagementService.SkillException("SKILL_PATH_INVALID", "absolute path is not allowed");
        String normalized = path.normalize().toString().replace('\\', '/');
        if (normalized.startsWith("../") || normalized.equals("..") || normalized.isBlank()) {
            throw new SkillManagementService.SkillException("SKILL_PATH_INVALID", "path traversal is not allowed");
        }
        return normalized;
    }
    private static boolean isText(byte[] bytes) {
        int controls = 0;
        for (byte raw : bytes) {
            int value = raw & 0xff;
            if (value == 0) return false;
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t') controls++;
        }
        return controls <= Math.max(2, bytes.length / 100);
    }
    private static int tokenEstimate(String content) { return Math.max(1, (content.codePointCount(0, content.length()) + 2) / 3); }
    private static String truncateToTokens(String content, int tokens) {
        if (tokens <= 0) return "";
        int maximumCodepoints = tokens * 3;
        int count = content.codePointCount(0, content.length());
        return count <= maximumCodepoints ? content : content.substring(0, content.offsetByCodePoints(0, maximumCodepoints));
    }
    private static SkillManagementService.SkillException limit(String code) {
        return new SkillManagementService.SkillException(code, "Skill disclosure limit exceeded");
    }
}
