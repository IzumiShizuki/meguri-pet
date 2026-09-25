package com.meguri.core.retrieval;

@FunctionalInterface
public interface KnowledgeVectorizer {
    double[] vectorize(String text);

    static KnowledgeVectorizer deterministicHashing(int dimensions) {
        if (dimensions < 16 || dimensions > 4096) {
            throw new IllegalArgumentException("vector dimensions must be between 16 and 4096");
        }
        return text -> {
            double[] vector = new double[dimensions];
            for (String token : KnowledgeRepositoryRetrievalBridge.tokens(text)) {
                int hash = token.hashCode();
                int index = Math.floorMod(hash, dimensions);
                vector[index] += (hash & 1) == 0 ? 1.0 : -1.0;
            }
            double norm = 0;
            for (double value : vector) norm += value * value;
            if (norm > 0) {
                norm = Math.sqrt(norm);
                for (int index = 0; index < vector.length; index++) vector[index] /= norm;
            }
            return vector;
        };
    }
}
