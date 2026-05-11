package com.wisewallet.transaction.presentation.dto.response;

import com.wisewallet.transaction.domain.model.TransactionCategory;

import java.math.BigDecimal;

public record CategorySummary(
        TransactionCategory category,
        BigDecimal total,
        long transactionCount
) {
}
