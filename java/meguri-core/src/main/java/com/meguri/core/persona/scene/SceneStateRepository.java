package com.meguri.core.persona.scene;

import com.meguri.core.persona.PersonaMutation;
import java.util.Optional;

public interface SceneStateRepository {
    Optional<SceneState> findScene(String conversationId);
    SceneState save(SceneState state);
    default SceneState save(SceneState previous, SceneState state, long expectedVersion, PersonaMutation mutation) {
        return save(state);
    }
}
