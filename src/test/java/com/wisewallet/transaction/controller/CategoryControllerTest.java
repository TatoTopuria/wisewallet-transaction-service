package com.wisewallet.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.command.CategoryCommandService;
import com.wisewallet.transaction.domain.exception.BusinessRuleException;
import com.wisewallet.transaction.domain.exception.TransactionNotFoundException;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.presentation.controller.CategoryController;
import com.wisewallet.transaction.presentation.dto.request.UpdateCategoryRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CategoryController.class)
@Import(GlobalExceptionHandler.class)
class CategoryControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean CategoryCommandService categoryService;

    private static final String USER_ID = UUID.randomUUID().toString();

    @Test
    void updateCategory_happyPath_returns200() throws Exception {
        var txnId = UUID.randomUUID();
        var response = new TransactionResponse(txnId, UUID.randomUUID(), null,
                BigDecimal.TEN, "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                TransactionCategory.GROCERIES, Instant.now());

        when(categoryService.updateCategory(eq(txnId), any(), any())).thenReturn(response);
        var request = new UpdateCategoryRequest(TransactionCategory.GROCERIES);

        mockMvc.perform(put("/api/transactions/" + txnId + "/category")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("GROCERIES"));
    }

    @Test
    void updateCategory_transactionNotFound_returns404() throws Exception {
        var txnId = UUID.randomUUID();

        when(categoryService.updateCategory(eq(txnId), any(), any()))
                .thenThrow(new TransactionNotFoundException("Transaction not found"));

        var request = new UpdateCategoryRequest(TransactionCategory.GROCERIES);

        mockMvc.perform(put("/api/transactions/" + txnId + "/category")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateCategory_notCompleted_returns422() throws Exception {
        var txnId = UUID.randomUUID();

        when(categoryService.updateCategory(eq(txnId), any(), any()))
                .thenThrow(new BusinessRuleException("Transaction not COMPLETED"));

        var request = new UpdateCategoryRequest(TransactionCategory.GROCERIES);

        mockMvc.perform(put("/api/transactions/" + txnId + "/category")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateCategory_invalidCategory_returns400() throws Exception {
        var txnId = UUID.randomUUID();

        mockMvc.perform(put("/api/transactions/" + txnId + "/category")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":null}"))
                .andExpect(status().isBadRequest());
    }
}
