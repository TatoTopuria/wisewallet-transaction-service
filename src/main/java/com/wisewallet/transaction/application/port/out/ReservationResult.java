package com.wisewallet.transaction.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of a fund reservation at the Account Service.
 */
public record ReservationResult(UUID reservationId, BigDecimal availableBalance) {
}
