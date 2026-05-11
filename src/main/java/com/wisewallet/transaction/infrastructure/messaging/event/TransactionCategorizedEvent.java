package com.wisewallet.transaction.infrastructure.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka message payload for transaction categorization events (txn.categorized topic).
 */
public record TransactionCategorizedEvent(
        UUID eventId,
        UUID transactionId,
        UUID userId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String category,
        Instant categorizedAt
) {
}
