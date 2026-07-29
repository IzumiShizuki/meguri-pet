package com.meguri.core.persona.runtime;

import com.meguri.core.persona.PersonaMutation;
import java.util.Optional;

public interface InteractionStateRepository {
    Optional<InteractionStateSnapshot> findInteraction(String turnId);
    InteractionStateSnapshot freeze(InteractionStateSnapshot snapshot, PersonaMutation mutation);
}
