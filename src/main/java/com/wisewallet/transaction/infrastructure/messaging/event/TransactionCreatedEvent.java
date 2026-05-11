package com.wisewallet.transaction.infrastructure.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka message payload for transaction creation events (txn.created topic).
 */
public record TransactionCreatedEvent(
        UUID eventId,
        UUID transactionId,
        UUID transferId,
        UUID userId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String type,
        String status,
        Instant createdAt
) {
}
