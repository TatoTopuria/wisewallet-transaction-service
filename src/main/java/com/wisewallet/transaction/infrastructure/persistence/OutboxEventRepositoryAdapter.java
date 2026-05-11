package com.wisewallet.transaction.infrastructure.persistence;

import com.wisewallet.transaction.domain.model.OutboxEvent;
import com.wisewallet.transaction.domain.repository.OutboxEventRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryAdapter implements OutboxEventRepositoryPort {

    private final OutboxEventJpaRepository jpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent event) {
        return jpaRepository.save(event);
    }

    @Override
    public List<OutboxEvent> findPendingForUpdate(int batchSize) {
        return jpaRepository.findPendingForUpdate(batchSize);
    }
}
