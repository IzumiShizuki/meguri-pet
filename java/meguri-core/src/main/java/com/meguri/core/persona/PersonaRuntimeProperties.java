package com.meguri.core.persona;

import com.meguri.core.dto.Mode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("meguri.persona")
public class PersonaRuntimeProperties {
    private Mode defaultTemporalMode = Mode.WORK;
    private String policyRevision = "persona-policy-v1";
    private Duration sceneTtl = Duration.ofMinutes(30);
    private Duration sceneCooldown = Duration.ofMinutes(5);

    public Mode getDefaultTemporalMode() {
        return defaultTemporalMode;
    }

    public void setDefaultTemporalMode(Mode defaultTemporalMode) {
        this.defaultTemporalMode = required(defaultTemporalMode, "defaultTemporalMode");
    }

    public String getPolicyRevision() {
        return policyRevision;
    }

    public void setPolicyRevision(String policyRevision) {
        if (policyRevision == null || policyRevision.isBlank()) {
            throw new IllegalArgumentException("policyRevision must not be blank");
        }
        this.policyRevision = policyRevision;
    }

    public Duration getSceneTtl() {
        return sceneTtl;
    }

    public void setSceneTtl(Duration sceneTtl) {
        this.sceneTtl = positive(sceneTtl, "sceneTtl");
    }

    public Duration getSceneCooldown() {
        return sceneCooldown;
    }

    public void setSceneCooldown(Duration sceneCooldown) {
        this.sceneCooldown = positive(sceneCooldown, "sceneCooldown");
    }

    private static Duration positive(Duration value, String field) {
        required(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
