package com.wisewallet.transaction.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.domain.event.TransactionCategorizedDomainEvent;
import com.wisewallet.transaction.domain.event.TransactionCreatedDomainEvent;
import com.wisewallet.transaction.domain.model.OutboxEvent;
import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.repository.OutboxEventRepositoryPort;
import com.wisewallet.transaction.infrastructure.messaging.event.TransactionCategorizedEvent;
import com.wisewallet.transaction.infrastructure.messaging.event.TransactionCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Infrastructure listener that converts domain events to OutboxEvent records within
 * the current transaction (BEFORE_COMMIT phase), guaranteeing at-least-once delivery.
 */
@Component
@RequiredArgsConstructor
public class DomainEventToOutboxListener {

    private final OutboxEventRepositoryPort outboxEventRepository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handleTransactionCreated(TransactionCreatedDomainEvent domainEvent) {
        Transaction txn = domainEvent.transaction();
        TransactionCreatedEvent payload = new TransactionCreatedEvent(
                UUID.randomUUID(),
                txn.getId(),
                txn.getTransferId(),
                txn.getUserId(),
                txn.getAccountId(),
                txn.getAmount(),
                txn.getCurrency(),
                txn.getType().name(),
                txn.getStatus().name(),
                txn.getCreatedAt()
        );
        outboxEventRepository.save(buildEvent(txn.getId(), "txn.created", payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handleTransactionCategorized(TransactionCategorizedDomainEvent domainEvent) {
        Transaction txn = domainEvent.transaction();
        TransactionCategorizedEvent payload = new TransactionCategorizedEvent(
                UUID.randomUUID(),
                txn.getId(),
                txn.getUserId(),
                txn.getAccountId(),
                txn.getAmount(),
                txn.getCurrency(),
                txn.getCategory() != null ? txn.getCategory().name() : null,
                Instant.now()
        );
        outboxEventRepository.save(buildEvent(txn.getId(), "txn.categorized", payload));
    }

    private OutboxEvent buildEvent(UUID aggregateId, String eventType, Object payload) {
        try {
            return OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status("PENDING")
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
    }
}
