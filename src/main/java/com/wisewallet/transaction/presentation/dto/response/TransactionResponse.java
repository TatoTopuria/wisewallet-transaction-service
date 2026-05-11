package com.wisewallet.transaction.presentation.dto.response;

import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        UUID transferId,
        BigDecimal amount,
        String currency,
        TransactionType type,
        TransactionStatus status,
        TransactionCategory category,
        Instant createdAt
) {
}
