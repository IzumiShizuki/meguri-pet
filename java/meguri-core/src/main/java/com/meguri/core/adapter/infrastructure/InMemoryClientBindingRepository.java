package com.meguri.core.adapter.infrastructure;

import com.meguri.core.adapter.application.ClientBindingRepository;
import com.meguri.core.adapter.domain.ClientBinding;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryClientBindingRepository implements ClientBindingRepository {
    private final ConcurrentMap<String, ClientBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public ClientBinding save(ClientBinding binding) {
        bindings.compute(binding.clientInstanceId(), (ignored, current) -> {
            if (current != null
                    && (!current.tenantId().equals(binding.tenantId())
                    || !current.meguriUserId().equals(binding.meguriUserId())
                    || !current.clientId().equals(binding.clientId())
                    || actorBindingChanged(current, binding))) {
                throw new IllegalStateException(
                        "client instance is already bound to another identity");
            }
            return binding;
        });
        return binding;
    }

    @Override
    public Optional<ClientBinding> find(String clientInstanceId) {
        return Optional.ofNullable(bindings.get(clientInstanceId));
    }

    private static boolean actorBindingChanged(
            ClientBinding current, ClientBinding incoming) {
        return current.platformActorHash() != null
                && !current.platformActorHash().equals(incoming.platformActorHash());
    }
}
