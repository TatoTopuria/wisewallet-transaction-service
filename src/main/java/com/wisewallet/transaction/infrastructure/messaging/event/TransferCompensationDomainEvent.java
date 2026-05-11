package com.wisewallet.transaction.infrastructure.messaging.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Domain event carrying all data needed to compensate a failed transfer.
 * Written to the outbox with eventType "txn.compensation.needed" so the
 * CompensationEventConsumer can reverse the credit / release the reservation.
 */
public record TransferCompensationDomainEvent(
        UUID transferId,
        UUID reservationId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        UUID creditTransactionId
) {}
