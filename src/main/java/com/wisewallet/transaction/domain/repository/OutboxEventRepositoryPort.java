package com.wisewallet.transaction.domain.repository;

import com.wisewallet.transaction.domain.model.OutboxEvent;

import java.util.List;

public interface OutboxEventRepositoryPort {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findPendingForUpdate(int batchSize);
}
