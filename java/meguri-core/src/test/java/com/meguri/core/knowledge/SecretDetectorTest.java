package com.meguri.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecretDetectorTest {
    private final SecretDetector detector = new SecretDetector();

    @Test
    void detectsCommonCredentialFamiliesAndCredentialBearingDatabaseUris() {
        assertSecret("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature");
        assertSecret("Cookie: session_id=0123456789abcdef0123456789abcdef; theme=light");
        assertSecret("Cookie: theme=light; session_id=0123456789abcdef0123456789abcdef");
        assertSecret("Set-Cookie: auth_token=0123456789abcdef; HttpOnly; Secure");
        assertSecret("ntn_abcdefghijklmnopqrstuvwxyz123456");
        assertSecret("ghp_abcdefghijklmnopqrstuvwxyz123456");
        assertSecret("github_pat_11ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnop");
        assertSecret("aws_access_key_id=AKIAIOSFODNN7EXAMPLE");
        assertSecret("aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        assertSecret("aws_session_token=IQoJb3JpZ2luX2VjEExampleSessionToken1234567890");
        assertSecret("jdbc:postgresql://meguri:supersafe123@db.example.test:5432/app");
        assertSecret("postgresql://db.example.test/app?user=meguri&password=supersafe123");
    }

    @Test
    void ignoresDocumentationAndNonCredentialConfiguration() {
        assertSafe("Use Authorization: Bearer <token> when authentication is enabled.");
        assertSafe("Cookie: theme=light; locale=zh-CN");
        assertSafe("password validation should require twelve characters");
        assertSafe("postgresql://db.example.test:5432/app");
        assertSafe("The GitHub token and Notion token are configured outside this page.");
        assertSafe("AKIA is an Indonesian name and not a credential by itself.");
    }

    private void assertSecret(String content) {
        assertThat(detector.containsSecret(page(content))).as(content).isTrue();
    }

    private void assertSafe(String content) {
        assertThat(detector.containsSecret(page(content))).as(content).isFalse();
    }

    private static SourcePage page(String content) {
        return new SourcePage(
                "test", "page-1", "Security notes", content, "a".repeat(64),
                Instant.parse("2026-07-28T12:00:00Z"),
                new KnowledgeAcl("meguri", Set.of("user:izumi")), false, false);
    }
}
