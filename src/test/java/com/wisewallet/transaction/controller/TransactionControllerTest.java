package com.wisewallet.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.command.DepositCommandService;
import com.wisewallet.transaction.application.command.TransferCommandService;
import com.wisewallet.transaction.application.command.WithdrawalCommandService;
import com.wisewallet.transaction.application.query.TransactionQueryService;
import com.wisewallet.transaction.domain.exception.DuplicateIdempotencyKeyException;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.presentation.controller.TransactionController;
import com.wisewallet.transaction.presentation.dto.request.DepositRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
class TransactionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean DepositCommandService depositService;
    @MockitoBean WithdrawalCommandService withdrawalService;
    @MockitoBean TransferCommandService transferService;
    @MockitoBean TransactionQueryService transactionQueryService;

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String ACCOUNT_ID = UUID.randomUUID().toString();

    @Test
    void deposit_missingIdempotencyKey_returns400() throws Exception {
        var request = new DepositRequest(UUID.fromString(ACCOUNT_ID), new BigDecimal("100.00"), "USD", null);

        mockMvc.perform(post("/api/transactions/deposit")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deposit_accountNotInClaims_returns403() throws Exception {
        var request = new DepositRequest(UUID.randomUUID(), new BigDecimal("100.00"), "USD", null);

        mockMvc.perform(post("/api/transactions/deposit")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID) // different from request's accountId
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deposit_duplicateIdempotencyKey_returns409() throws Exception {
        var request = new DepositRequest(UUID.fromString(ACCOUNT_ID), new BigDecimal("100.00"), "USD", null);

        when(depositService.deposit(any(), any(), any()))
                .thenThrow(new DuplicateIdempotencyKeyException("already in progress"));

        mockMvc.perform(post("/api/transactions/deposit")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void deposit_happyPath_returns201() throws Exception {
        var accountId = UUID.fromString(ACCOUNT_ID);
        var request = new DepositRequest(accountId, new BigDecimal("100.00"), "USD", null);
        var response = new TransactionResponse(UUID.randomUUID(), accountId, null,
                new BigDecimal("100.00"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                TransactionCategory.OTHER, Instant.now());

        when(depositService.deposit(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/transactions/deposit")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void deposit_invalidAmount_returns400() throws Exception {
        var body = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"amount\":\"0.00\"}";

        mockMvc.perform(post("/api/transactions/deposit")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }
}
