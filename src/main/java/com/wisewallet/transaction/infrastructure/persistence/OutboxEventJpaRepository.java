package com.wisewallet.transaction.infrastructure.persistence;

import com.wisewallet.transaction.domain.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventJpaRepository
        extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            SELECT * FROM transaction.txn_outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForUpdate(@Param("batchSize") int batchSize);

}
