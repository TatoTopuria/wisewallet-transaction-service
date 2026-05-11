package com.wisewallet.transaction.infrastructure.persistence;

import com.wisewallet.transaction.domain.model.IdempotencyKey;
import com.wisewallet.transaction.domain.repository.IdempotencyKeyRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IdempotencyKeyRepositoryAdapter implements IdempotencyKeyRepositoryPort {

    private final IdempotencyKeyJpaRepository jpaRepository;

    @Override
    public IdempotencyKey saveAndFlush(IdempotencyKey key) {
        return jpaRepository.saveAndFlush(key);
    }

    @Override
    public IdempotencyKey save(IdempotencyKey key) {
        return jpaRepository.save(key);
    }

    @Override
    public Optional<IdempotencyKey> findByKeyAndUserId(String key, UUID userId) {
        return jpaRepository.findByKeyAndUserId(key, userId);
    }

    @Override
    public int deleteExpired(Instant now) {
        return jpaRepository.deleteExpired(now);
    }
}
