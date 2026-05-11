package com.wisewallet.transaction.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Output port: defines the contract this service has with the Account Service.
 * Infrastructure provides the implementation via Feign.
 */
public interface AccountServicePort {

    AccountInfo getAccount(UUID accountId);

    void credit(UUID accountId, BigDecimal amount, String currency, UUID transactionId);

    void debit(UUID accountId, BigDecimal amount, String currency, UUID transactionId);

    ReservationResult reserve(UUID accountId, BigDecimal amount, String currency, UUID transactionId);

    void commit(UUID accountId, UUID reservationId, String currency);

    void release(UUID accountId, UUID reservationId, String currency);
}
