package com.wisewallet.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.command.DepositCommandService;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.application.shared.IdempotencyService;
import com.wisewallet.transaction.application.shared.IdempotencyService.IdempotencyResult;
import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.domain.service.CategoryRuleEngine;
import com.wisewallet.transaction.presentation.dto.request.DepositRequest;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock TransactionRepositoryPort transactionRepository;
    @Mock AccountServicePort accountServiceClient;
    @Mock CategoryRuleEngine categoryRuleEngine;
    @Mock IdempotencyService idempotencyService;
    @Mock TransactionMapper transactionMapper;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock ObjectMapper objectMapper;

    @InjectMocks DepositCommandService depositService;

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
    void deposit_happyPath_returnsTransactionResponse() throws Exception {
        var request = new DepositRequest(accountId, new BigDecimal("100.00"), "USD", null);
        var txn = buildTransaction();
        var response = buildResponse(txn);

        when(idempotencyService.checkOrInsert(idempotencyKey, userId))
                .thenReturn(new IdempotencyResult.Proceed());
        doNothing().when(accountServiceClient).credit(eq(accountId), any(java.math.BigDecimal.class), any(String.class), any(java.util.UUID.class));
        when(categoryRuleEngine.categorize(TransactionType.DEPOSIT, null, null))
                .thenReturn(TransactionCategory.OTHER);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(txn);
        when(transactionMapper.toResponse(any())).thenReturn(response);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        var result = depositService.deposit(userId, idempotencyKey, request);

        assertThat(result).isNotNull();
        verify(accountServiceClient).credit(eq(accountId), any(java.math.BigDecimal.class), any(String.class), any(java.util.UUID.class));
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void deposit_idempotencyReplay_returnsCachedResponse() throws Exception {
        var request = new DepositRequest(accountId, new BigDecimal("100.00"), "USD", null);
        var cachedJson = "{\"id\":\"" + UUID.randomUUID() + "\"}";
        var cachedResponse = new TransactionResponse(UUID.randomUUID(), accountId, null,
                BigDecimal.TEN, "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER, Instant.now());

        when(idempotencyService.checkOrInsert(idempotencyKey, userId))
                .thenReturn(new IdempotencyResult.Cached(201, cachedJson));
        when(objectMapper.readValue(cachedJson, TransactionResponse.class)).thenReturn(cachedResponse);

        var result = depositService.deposit(userId, idempotencyKey, request);

        assertThat(result).isEqualTo(cachedResponse);
        verifyNoInteractions(accountServiceClient);
    }

    private Transaction buildTransaction() {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .accountId(accountId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEPOSIT)
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
