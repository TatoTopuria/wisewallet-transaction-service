package com.wisewallet.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.command.WithdrawalCommandService;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.application.shared.IdempotencyService;
import com.wisewallet.transaction.application.shared.IdempotencyService.IdempotencyResult;
import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.domain.service.CategoryRuleEngine;
import com.wisewallet.transaction.presentation.dto.request.WithdrawalRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock TransactionRepositoryPort transactionRepository;
    @Mock AccountServicePort accountServiceClient;
    @Mock CategoryRuleEngine categoryRuleEngine;
    @Mock IdempotencyService idempotencyService;
    @Mock TransactionMapper transactionMapper;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock ObjectMapper objectMapper;

    @InjectMocks WithdrawalCommandService withdrawalService;

    private UUID userId;
    private UUID accountId;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();
    }

    @Test
    void withdraw_happyPath_negativesAmount() throws Exception {
        var request = new WithdrawalRequest(accountId, new BigDecimal("50.00"), "USD", null);
        var txn = buildTransaction();
        var response = buildResponse(txn);

        when(idempotencyService.checkOrInsert(idempotencyKey, userId))
                .thenReturn(new IdempotencyResult.Proceed());
        when(categoryRuleEngine.categorize(TransactionType.WITHDRAWAL, null, null))
                .thenReturn(TransactionCategory.OTHER);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(txn);
        when(transactionMapper.toResponse(any())).thenReturn(response);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        var result = withdrawalService.withdraw(userId, idempotencyKey, request);

        assertThat(result).isNotNull();
        verify(accountServiceClient).debit(eq(accountId), any(java.math.BigDecimal.class), any(String.class), any(java.util.UUID.class));
    }

    @Test
    void withdraw_insufficientFunds_propagates422() {
        var request = new WithdrawalRequest(accountId, new BigDecimal("9999.00"), "USD", null);

        when(idempotencyService.checkOrInsert(idempotencyKey, userId))
                .thenReturn(new IdempotencyResult.Proceed());

        doThrow(new com.wisewallet.transaction.domain.exception.InsufficientFundsException("Insufficient funds"))
                .when(accountServiceClient).debit(eq(accountId), any(java.math.BigDecimal.class), any(String.class), any(java.util.UUID.class));

        assertThatThrownBy(() -> withdrawalService.withdraw(userId, idempotencyKey, request))
                .isInstanceOf(com.wisewallet.transaction.domain.exception.InsufficientFundsException.class);
    }

    private Transaction buildTransaction() {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .accountId(accountId)
                .amount(new BigDecimal("-50.00"))
                .type(TransactionType.WITHDRAWAL)
                .status(TransactionStatus.COMPLETED)
                .category(TransactionCategory.OTHER)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private TransactionResponse buildResponse(Transaction txn) {
        return new TransactionResponse(txn.getId(), txn.getAccountId(), null,
                txn.getAmount(), "USD", txn.getType(), txn.getStatus(), txn.getCategory(), Instant.now());
    }
}
