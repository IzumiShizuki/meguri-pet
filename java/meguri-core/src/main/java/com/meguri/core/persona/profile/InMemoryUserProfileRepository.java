package com.meguri.core.persona.profile;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryUserProfileRepository implements UserProfileRepository {
    private final ConcurrentHashMap<String, UserProfile> profiles =
            new ConcurrentHashMap<>();

    @Override
    public Optional<UserProfile> find(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public void save(UserProfile profile) {
        profiles.put(profile.userId(), profile);
    }
}
