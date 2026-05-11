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
import com.wisewallet.transaction.presentation.dto.request.DepositRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DepositCommandService {

    private static final Logger log = LoggerFactory.getLogger(DepositCommandService.class);

    private final TransactionRepositoryPort transactionRepository;
    private final AccountServicePort accountServicePort;
    private final CategoryRuleEngine categoryRuleEngine;
    private final IdempotencyService idempotencyService;
    private final TransactionMapper transactionMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public TransactionResponse deposit(UUID userId, String idempotencyKey, DepositRequest request) {
        IdempotencyResult result = idempotencyService.checkOrInsert(idempotencyKey, userId);

        if (result instanceof IdempotencyResult.Cached cached) {
            try {
                return objectMapper.readValue(cached.responseBody(), TransactionResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
            }
        }

        return executeDeposit(userId, idempotencyKey, request);
    }

    protected TransactionResponse executeDeposit(UUID userId, String idempotencyKey, DepositRequest request) {
        UUID txnId = UUID.randomUUID();
        accountServicePort.credit(request.accountId(), request.amount(), request.currency(), txnId);

        var category = categoryRuleEngine.categorize(TransactionType.DEPOSIT, request.mccCode(), null);

        Transaction txn = Transaction.builder()
                .id(txnId)
                .userId(userId)
                .accountId(request.accountId())
                .amount(request.amount())
                .currency(request.currency())
                .type(TransactionType.DEPOSIT)
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
}
