package com.wisewallet.transaction.domain.repository;

import com.wisewallet.transaction.domain.model.IdempotencyKey;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepositoryPort {

    IdempotencyKey saveAndFlush(IdempotencyKey key);

    IdempotencyKey save(IdempotencyKey key);

    Optional<IdempotencyKey> findByKeyAndUserId(String key, UUID userId);

    int deleteExpired(Instant now);
}
