package com.wisewallet.transaction.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

public interface CompensationOutboxPort {

    void scheduleCompensation(UUID transferId,
                              UUID reservationId,
                              UUID sourceAccountId,
                              UUID destinationAccountId,
                              BigDecimal amount,
                              String currency,
                              UUID creditTransactionId);
}
