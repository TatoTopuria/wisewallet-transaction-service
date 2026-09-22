package com.wisewallet.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.command.TransferCommandService;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.application.port.out.ReservationResult;
import com.wisewallet.transaction.application.shared.IdempotencyService;
import com.wisewallet.transaction.application.shared.IdempotencyService.IdempotencyResult;
import com.wisewallet.transaction.domain.exception.BusinessRuleException;
import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.presentation.dto.request.TransferRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.dto.response.TransferResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import feign.FeignException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceTest {

    @Mock TransactionRepositoryPort transactionRepository;
    @Mock AccountServicePort accountServicePort;
    @Mock IdempotencyService idempotencyService;
    @Mock TransactionMapper transactionMapper;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock MeterRegistry meterRegistry;
    @Mock ObjectMapper objectMapper;

    @InjectMocks TransferCommandService transferService;

    private UUID userId;
    private UUID sourceId;
    private UUID destId;
    private String idempotencyKey;
    private BigDecimal amount;

    @BeforeEach
    void setUp() throws Exception {
        userId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        destId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();
        amount = new BigDecimal("100.00");

        when(idempotencyService.checkOrInsert(idempotencyKey, userId))
                .thenReturn(new IdempotencyResult.Proceed());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            return t;
        });
        when(transactionMapper.toResponse(any(Transaction.class))).thenReturn(mock(TransactionResponse.class));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test
    void transfer_happyPath_bothLegsCompleted() throws Exception {
        var request = new TransferRequest(sourceId, destId, amount, "USD");
        var reservationId = UUID.randomUUID();
        var reserveResponse = new ReservationResult(reservationId, new java.math.BigDecimal("900.00"));

        when(accountServicePort.reserve(eq(sourceId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class))).thenReturn(reserveResponse);
        doNothing().when(accountServicePort).credit(eq(destId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class));
        doNothing().when(accountServicePort).commit(eq(sourceId), any(UUID.class), any(String.class));
        when(transactionMapper.toResponse(any())).thenReturn(mock(TransactionResponse.class));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        var result = transferService.transfer(userId, idempotencyKey, request);

        assertThat(result).isNotNull();
        verify(accountServicePort).reserve(eq(sourceId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class));
        verify(accountServicePort).credit(eq(destId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class));
        verify(accountServicePort).commit(eq(sourceId), any(UUID.class), any(String.class));
    }

    @Test
    void transfer_reservationFails_bothLegsMarkedFailed() throws Exception {
        var request = new TransferRequest(sourceId, destId, amount, "USD");

        var feignEx = mock(FeignException.UnprocessableEntity.class);
        when(accountServicePort.reserve(eq(sourceId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class))).thenThrow(feignEx);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        assertThatThrownBy(() -> transferService.transfer(userId, idempotencyKey, request))
                .isInstanceOf(BusinessRuleException.class);

        verify(accountServicePort, never()).credit(any(), any(), any(), any());
    }

    @Test
    void transfer_creditFails_releaseCalled_bothLegsMarkedFailed() throws Exception {
        var request = new TransferRequest(sourceId, destId, amount, "USD");
        var reservationId = UUID.randomUUID();
        var reserveResponse = new ReservationResult(reservationId, new java.math.BigDecimal("900.00"));

        when(accountServicePort.reserve(eq(sourceId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class))).thenReturn(reserveResponse);
        var feignEx = mock(FeignException.UnprocessableEntity.class);
        doThrow(feignEx).when(accountServicePort).credit(eq(destId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        assertThatThrownBy(() -> transferService.transfer(userId, idempotencyKey, request))
                .isInstanceOf(BusinessRuleException.class);

        verify(accountServicePort).release(eq(sourceId), any(UUID.class), any(String.class));
    }

    @Test
    void transfer_commitFails_metricsIncrementedAndExceptionThrown() throws Exception {
        var request = new TransferRequest(sourceId, destId, amount, "USD");
        var reservationId = UUID.randomUUID();
        var reserveResponse = new ReservationResult(reservationId, new java.math.BigDecimal("900.00"));
        var counter = mock(Counter.class);

        when(accountServicePort.reserve(eq(sourceId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class))).thenReturn(reserveResponse);
        doNothing().when(accountServicePort).credit(eq(destId), any(java.math.BigDecimal.class), any(String.class), any(UUID.class));
        doThrow(new RuntimeException("commit timeout")).when(accountServicePort)
                .commit(eq(sourceId), any(UUID.class), any(String.class));
        when(meterRegistry.counter("transfer.commit.failure.count")).thenReturn(counter);

        assertThatThrownBy(() -> transferService.transfer(userId, idempotencyKey, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("commit failed");

        verify(counter).increment();
    }

    @Test
    void transfer_idempotencyReplay_returnsCachedResponse() throws Exception {
        var request = new TransferRequest(sourceId, destId, amount, "USD");
        var cachedTransferId = UUID.randomUUID();
        var cachedJson = "{\"transferId\":\"" + cachedTransferId + "\"}";
        var cachedResponse = new TransferResponse(cachedTransferId, List.of());

        when(idempotencyService.checkOrInsert(idempotencyKey, userId))
                .thenReturn(new IdempotencyResult.Cached(201, cachedJson));
        when(objectMapper.readValue(cachedJson, TransferResponse.class)).thenReturn(cachedResponse);

        var result = transferService.transfer(userId, idempotencyKey, request);

        assertThat(result.transferId()).isEqualTo(cachedTransferId);
        verifyNoInteractions(accountServicePort);
    }
}
