package com.meguri.core.adapter.application;

import com.meguri.core.adapter.domain.ClientBinding;

import java.util.Optional;

public interface ClientBindingRepository {
    ClientBinding save(ClientBinding binding);

    Optional<ClientBinding> find(String clientInstanceId);
}
