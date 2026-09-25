package com.meguri.core.knowledge;

import java.util.List;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/** Blocks common credential shapes before any source metadata reaches persistence. */
public final class SecretDetector {
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)\\bsk-[a-z0-9_-]{20,}\\b"),
            Pattern.compile("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)\\bauthorization\\s*:\\s*bearer\\s+[a-z0-9._~+/=-]{16,}"),
            Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{24,}"),
            Pattern.compile("(?i)\\b(?:cookie|set-cookie)\\s*:\\s*(?:[^;\\r\\n]*;\\s*)*(?:session|auth|token|jwt|sid)[^=;\\r\\n]*=[^;\\s]{12,}"),
            Pattern.compile("\\b(?:ntn_[A-Za-z0-9_-]{20,}|secret_[A-Za-z0-9]{32,})\\b"),
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{40,})\\b"),
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("(?i)\\baws_secret_access_key\\s*[:=]\\s*[A-Za-z0-9/+=]{32,}"),
            Pattern.compile("(?i)\\baws_session_token\\s*[:=]\\s*[A-Za-z0-9/+=]{32,}"),
            Pattern.compile("(?i)\\b(?:jdbc:)?(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|redis)://[^\\s/:@]+:[^\\s/@]{6,}@[^\\s]+"),
            Pattern.compile("(?i)\\b(?:jdbc:)?(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|redis)://[^\\s]+[?&](?:password|passwd|pwd)=[^&\\s]{6,}"),
            Pattern.compile("(?i)\\b(?:password|passwd|api[_-]?key|access[_-]?token)\\s*[:=]\\s*(?:['\"])?(?=\\S{12,})(?=\\S*[A-Za-z])(?=\\S*\\d)[^\\s'\"]{12,}"));

    public boolean containsSecret(SourcePage page) {
        if (page.secretMode()) return true;
        KnowledgeSourceMetadata metadata = page.metadata();
        Stream<String> values = Stream.concat(
                Stream.of(
                        page.title(),
                        page.content(),
                        metadata.sourceUri(),
                        metadata.canonicalUri(),
                        metadata.language(),
                        metadata.projectId(),
                        metadata.sourceParentId()),
                metadata.linkedDocumentIds().stream());
        List<String> material = values.filter(java.util.Objects::nonNull).toList();
        return SECRET_PATTERNS.stream().anyMatch(pattern ->
                material.stream().anyMatch(value -> pattern.matcher(value).find()));
    }
}
