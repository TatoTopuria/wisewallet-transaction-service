package com.wisewallet.transaction.controller;

import com.wisewallet.transaction.application.command.CategoryCommandService;
import com.wisewallet.transaction.application.command.DepositCommandService;
import com.wisewallet.transaction.application.command.TransferCommandService;
import com.wisewallet.transaction.application.command.WithdrawalCommandService;
import com.wisewallet.transaction.application.query.TransactionQueryService;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.presentation.controller.TransactionController;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
class TransactionListControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DepositCommandService depositService;
    @MockitoBean WithdrawalCommandService withdrawalService;
    @MockitoBean TransferCommandService transferService;
    @MockitoBean TransactionQueryService transactionQueryService;
    @MockitoBean CategoryCommandService categoryService;

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String ACCOUNT_ID = UUID.randomUUID().toString();

    @Test
    void listTransactions_happyPath_returnsPaginatedResponse() throws Exception {
        var txnId = UUID.randomUUID();
        var response = new TransactionResponse(txnId, UUID.fromString(ACCOUNT_ID), null,
                BigDecimal.TEN, "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                TransactionCategory.OTHER, Instant.now());

        var page = new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1);
        when(transactionQueryService.listTransactions(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/transactions")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .param("accountId", ACCOUNT_ID)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(txnId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listTransactions_accountNotInClaims_returns403() throws Exception {
        var otherAccountId = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/transactions")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .param("accountId", otherAccountId))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTransactions_defaultPagination_appliesDefaults() throws Exception {
        var page = new PageImpl<TransactionResponse>(List.of(), PageRequest.of(0, 20), 0);
        when(transactionQueryService.listTransactions(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/transactions")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .param("accountId", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listTransactions_sizeExceedsCap_cappedAt100() throws Exception {
        var page = new PageImpl<TransactionResponse>(List.of(), PageRequest.of(0, 100), 0);
        when(transactionQueryService.listTransactions(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/transactions")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .param("accountId", ACCOUNT_ID)
                .param("size", "500"))
                .andExpect(status().isOk());
    }

    @Test
    void listTransactions_withCategoryFilter_passesThrough() throws Exception {
        var page = new PageImpl<TransactionResponse>(List.of(), PageRequest.of(0, 20), 0);
        when(transactionQueryService.listTransactions(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/transactions")
                .header("X-User-Id", USER_ID)
                .header("X-Account-Ids", ACCOUNT_ID)
                .param("accountId", ACCOUNT_ID)
                .param("category", "GROCERIES", "DINING"))
                .andExpect(status().isOk());
    }
}
