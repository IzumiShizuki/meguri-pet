package com.meguri.core.retrieval;

import java.util.List;

/** Ordered graph path retained with an item for citation and replay. */
public record GraphPath(List<String> nodeIds, List<Edge> edges, double score) {
    public GraphPath {
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        edges = edges == null ? List.of() : List.copyOf(edges);
        if (edges.size() < 1 || edges.size() > 3 || nodeIds.size() != edges.size() + 1) {
            throw new IllegalArgumentException("graph path must contain 1-3 ordered edges");
        }
        for (int index = 0; index < edges.size(); index++) {
            Edge edge = edges.get(index);
            if (!nodeIds.get(index).equals(edge.fromId())
                    || !nodeIds.get(index + 1).equals(edge.toId())) {
                throw new IllegalArgumentException("graph edges must follow nodeIds order");
            }
        }
        if (!Double.isFinite(score)) throw new IllegalArgumentException("path score must be finite");
    }

    public int hops() {
        return edges.size();
    }

    public record Edge(String edgeId, String fromId, String relation, String toId,
                       List<String> evidenceChunkIds, double score) {
        public Edge {
            if (blank(edgeId) || blank(fromId) || blank(relation) || blank(toId)) {
                throw new IllegalArgumentException("graph edge identifiers and relation are required");
            }
            evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
            if (!Double.isFinite(score)) throw new IllegalArgumentException("edge score must be finite");
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
