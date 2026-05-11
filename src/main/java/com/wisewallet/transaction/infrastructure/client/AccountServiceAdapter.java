package com.wisewallet.transaction.infrastructure.client;

import com.wisewallet.transaction.application.port.out.AccountInfo;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.application.port.out.ReservationResult;
import com.wisewallet.transaction.infrastructure.client.dto.CommitReleaseRequest;
import com.wisewallet.transaction.infrastructure.client.dto.DebitCreditRequest;
import com.wisewallet.transaction.infrastructure.client.dto.InternalAccountResponse;
import com.wisewallet.transaction.infrastructure.client.dto.ReserveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter that implements the AccountServicePort by delegating
 * to the AccountServiceFeignClient. Translates infrastructure DTOs to/from
 * the domain port contract.
 */
@Component
@RequiredArgsConstructor
public class AccountServiceAdapter implements AccountServicePort {

    private final AccountServiceFeignClient feignClient;

    @Override
    public AccountInfo getAccount(UUID accountId) {
        InternalAccountResponse response = feignClient.getAccount(accountId);
        List<String> currencies = response.availableCurrencies() != null
                ? response.availableCurrencies()
                : Collections.emptyList();
        return new AccountInfo(
                response.id(),
                response.userId(),
                response.accountType(),
                response.status(),
                currencies
        );
    }

    @Override
    public void credit(UUID accountId, BigDecimal amount, String currency, UUID transactionId) {
        feignClient.credit(accountId, currency, new DebitCreditRequest(amount, transactionId));
    }

    @Override
    public void debit(UUID accountId, BigDecimal amount, String currency, UUID transactionId) {
        feignClient.debit(accountId, currency, new DebitCreditRequest(amount, transactionId));
    }

    @Override
    public ReservationResult reserve(UUID accountId, BigDecimal amount, String currency, UUID transactionId) {
        var response = feignClient.reserve(accountId, currency, new ReserveRequest(amount, transactionId));
        return new ReservationResult(response.reservationId(), response.availableBalance());
    }

    @Override
    public void commit(UUID accountId, UUID reservationId, String currency) {
        feignClient.commit(accountId, currency, new CommitReleaseRequest(reservationId));
    }

    @Override
    public void release(UUID accountId, UUID reservationId, String currency) {
        feignClient.release(accountId, currency, new CommitReleaseRequest(reservationId));
    }
}
