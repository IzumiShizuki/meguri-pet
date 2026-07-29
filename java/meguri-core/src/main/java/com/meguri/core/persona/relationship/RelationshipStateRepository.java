package com.meguri.core.persona.relationship;

import com.meguri.core.persona.PersonaMutation;
import java.util.Optional;

public interface RelationshipStateRepository {
    Optional<RelationshipState> findRelationship(String userId);
    RelationshipState save(RelationshipState state, long expectedVersion);
    default RelationshipState saveTransition(RelationshipState previous, RelationshipState state,
                                             long expectedVersion, PersonaMutation mutation) {
        return save(state, expectedVersion);
    }
}
