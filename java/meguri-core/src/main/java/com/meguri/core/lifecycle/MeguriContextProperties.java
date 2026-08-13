package com.meguri.core.lifecycle;

import com.meguri.core.context.ContextRefactoringStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feature flags for the new context projection; every capability is opt-in. */
@ConfigurationProperties("meguri.context")
public class MeguriContextProperties {
    private boolean structuredCompressionEnabled;
    private boolean selectiveRehydrationEnabled;
    private boolean topicDetectionEnabled;
    private boolean semanticCompressionEnabled;
    private String strategyRevision = ContextRefactoringStrategy.DEFAULT_REVISION;

    public boolean isStructuredCompressionEnabled() {
        return structuredCompressionEnabled;
    }

    public void setStructuredCompressionEnabled(boolean value) {
        structuredCompressionEnabled = value;
    }

    public boolean isSelectiveRehydrationEnabled() {
        return selectiveRehydrationEnabled;
    }

    public void setSelectiveRehydrationEnabled(boolean value) {
        selectiveRehydrationEnabled = value;
    }

    public boolean isTopicDetectionEnabled() {
        return topicDetectionEnabled;
    }

    public void setTopicDetectionEnabled(boolean value) {
        topicDetectionEnabled = value;
    }

    public boolean isSemanticCompressionEnabled() {
        return semanticCompressionEnabled;
    }

    public void setSemanticCompressionEnabled(boolean value) {
        semanticCompressionEnabled = value;
    }

    public String getStrategyRevision() {
        return strategyRevision;
    }

    public void setStrategyRevision(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("context.strategyRevision must not be blank");
        }
        strategyRevision = value.trim();
    }
}
