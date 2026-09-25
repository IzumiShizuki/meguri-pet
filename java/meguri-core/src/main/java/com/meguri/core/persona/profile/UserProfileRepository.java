package com.meguri.core.persona.profile;

import java.util.Optional;

/** Single persistence seam for the user-owned Persona input. */
public interface UserProfileRepository {
    Optional<UserProfile> find(String userId);

    void save(UserProfile profile);

    default UserProfile findOrEmpty(String userId) {
        return find(userId).orElseGet(() -> UserProfile.empty(userId));
    }
}
