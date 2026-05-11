package com.wisewallet.transaction.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.application.shared.IdempotencyService;
import com.wisewallet.transaction.application.shared.IdempotencyService.IdempotencyResult;
import com.wisewallet.transaction.domain.event.TransactionCategorizedDomainEvent;
import com.wisewallet.transaction.domain.event.TransactionCreatedDomainEvent;
import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.domain.service.CategoryRuleEngine;
import com.wisewallet.transaction.presentation.dto.request.WithdrawalRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalCommandService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalCommandService.class);

    private final TransactionRepositoryPort transactionRepository;
    private final AccountServicePort accountServicePort;
    private final CategoryRuleEngine categoryRuleEngine;
    private final IdempotencyService idempotencyService;
    private final TransactionMapper transactionMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${wisewallet.transaction.saga.feign-retry-attempts:3}")
    private int retryAttempts;

    @Value("${wisewallet.transaction.saga.feign-retry-backoff-ms:100}")
    private long retryBackoffMs;

    @Transactional
    public TransactionResponse withdraw(UUID userId, String idempotencyKey, WithdrawalRequest request) {
        IdempotencyResult result = idempotencyService.checkOrInsert(idempotencyKey, userId);

        if (result instanceof IdempotencyResult.Cached cached) {
            try {
                return objectMapper.readValue(cached.responseBody(), TransactionResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
            }
        }

        return executeWithdrawal(userId, idempotencyKey, request);
    }

    protected TransactionResponse executeWithdrawal(UUID userId, String idempotencyKey, WithdrawalRequest request) {
        UUID txnId = UUID.randomUUID();
        debitWithRetry(request.accountId(), request.amount(), request.currency(), txnId);

        var category = categoryRuleEngine.categorize(TransactionType.WITHDRAWAL, request.mccCode(), null);

        Transaction txn = Transaction.builder()
                .id(txnId)
                .userId(userId)
                .accountId(request.accountId())
                .amount(request.amount().negate())
                .currency(request.currency())
                .type(TransactionType.WITHDRAWAL)
                .status(TransactionStatus.COMPLETED)
                .category(category)
                .mccCode(request.mccCode())
                .idempotencyKey(idempotencyKey)
                .build();

        transactionRepository.save(txn);

        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(txn));
        eventPublisher.publishEvent(new TransactionCategorizedDomainEvent(txn));

        TransactionResponse response = transactionMapper.toResponse(txn);
        try {
            idempotencyService.complete(idempotencyKey, userId, 201, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("Failed to store idempotency response for key {}", idempotencyKey, e);
        }

        return response;
    }

    private void debitWithRetry(UUID accountId, BigDecimal amount, String currency, UUID transactionId) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                accountServicePort.debit(accountId, amount, currency, transactionId);
                return;
            } catch (FeignException.Conflict e) {
                if (attempt >= retryAttempts) {
                    throw e;
                }
                log.warn("Optimistic lock conflict on debit attempt {}/{}, retrying in {}ms",
                        attempt, retryAttempts, retryBackoffMs);
                try {
                    Thread.sleep(retryBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during debit retry", ie);
                }
            }
        }
    }
}
