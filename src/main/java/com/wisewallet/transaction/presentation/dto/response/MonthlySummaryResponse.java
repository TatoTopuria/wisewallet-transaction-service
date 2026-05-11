package com.wisewallet.transaction.presentation.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record MonthlySummaryResponse(
        int year,
        int month,
        BigDecimal totalSpent,
        BigDecimal totalIncome,
        BigDecimal netFlow,
        List<CategorySummary> byCategory
) {
}
