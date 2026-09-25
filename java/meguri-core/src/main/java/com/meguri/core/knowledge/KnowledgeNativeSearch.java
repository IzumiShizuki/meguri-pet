package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Optional database-native candidate ranking. Authorization is represented by
 * already-approved immutable version and ACL hashes; callers still validate
 * every returned chunk against the frozen projection.
 */
public interface KnowledgeNativeSearch {
    List<String> rankKeyword(
            Set<String> versionIds,
            Set<String> aclHashes,
            Instant validAt,
            String query,
            int limit);

    List<String> rankVector(
            Set<String> versionIds,
            Set<String> aclHashes,
            Instant validAt,
            List<Double> queryEmbedding,
            int limit);
}
