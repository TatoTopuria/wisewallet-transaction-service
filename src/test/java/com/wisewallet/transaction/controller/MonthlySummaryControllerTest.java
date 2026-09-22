package com.wisewallet.transaction.controller;

import com.wisewallet.transaction.application.command.CategoryCommandService;
import com.wisewallet.transaction.application.command.DepositCommandService;
import com.wisewallet.transaction.application.command.TransferCommandService;
import com.wisewallet.transaction.application.command.WithdrawalCommandService;
import com.wisewallet.transaction.application.query.TransactionQueryService;
import com.wisewallet.transaction.domain.exception.BusinessRuleException;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.presentation.controller.TransactionController;
import com.wisewallet.transaction.presentation.dto.response.CategorySummary;
import com.wisewallet.transaction.presentation.dto.response.MonthlySummaryResponse;
import com.wisewallet.transaction.presentation.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MonthlySummaryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DepositCommandService depositService;
    @MockitoBean WithdrawalCommandService withdrawalService;
    @MockitoBean TransferCommandService transferService;
    @MockitoBean TransactionQueryService transactionQueryService;
    @MockitoBean CategoryCommandService categoryService;

    private static final String USER_ID = UUID.randomUUID().toString();

    @Test
    void monthlySummary_happyPath_returnsCorrectShape() throws Exception {
        var now = LocalDate.now();
        var summary = new MonthlySummaryResponse(
                now.getYear(), now.getMonthValue(),
                new BigDecimal("500.00"), new BigDecimal("1000.00"), new BigDecimal("500.00"),
                List.of(new CategorySummary(TransactionCategory.GROCERIES, new BigDecimal("200.00"), 3))
        );

        when(transactionQueryService.monthlySummary(any(), anyInt(), anyInt())).thenReturn(summary);

        mockMvc.perform(get("/api/transactions/summary/monthly")
                .header("X-User-Id", USER_ID)
                .param("year", String.valueOf(now.getYear()))
                .param("month", String.valueOf(now.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(now.getYear()))
                .andExpect(jsonPath("$.month").value(now.getMonthValue()))
                .andExpect(jsonPath("$.totalSpent").value(500.00))
                .andExpect(jsonPath("$.totalIncome").value(1000.00))
                .andExpect(jsonPath("$.netFlow").value(500.00))
                .andExpect(jsonPath("$.byCategory").isArray())
                .andExpect(jsonPath("$.byCategory[0].category").value("GROCERIES"));
    }

    @Test
    void monthlySummary_outOfRangeMonth_returns422() throws Exception {
        when(transactionQueryService.monthlySummary(any(), anyInt(), anyInt()))
                .thenThrow(new BusinessRuleException("Date range exceeds 12 months"));

        mockMvc.perform(get("/api/transactions/summary/monthly")
                .header("X-User-Id", USER_ID)
                .param("year", "2020")
                .param("month", "1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void monthlySummary_defaultsToCurrentYearMonth_whenParamsOmitted() throws Exception {
        var now = LocalDate.now();
        var summary = new MonthlySummaryResponse(
                now.getYear(), now.getMonthValue(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()
        );

        when(transactionQueryService.monthlySummary(any(), eq(now.getYear()), eq(now.getMonthValue())))
                .thenReturn(summary);

        mockMvc.perform(get("/api/transactions/summary/monthly")
                .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(now.getYear()))
                .andExpect(jsonPath("$.month").value(now.getMonthValue()));
    }
}
