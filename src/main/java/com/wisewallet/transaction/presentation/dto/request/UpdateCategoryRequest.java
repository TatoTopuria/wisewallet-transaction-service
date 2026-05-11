package com.wisewallet.transaction.presentation.dto.request;

import com.wisewallet.transaction.domain.model.TransactionCategory;
import jakarta.validation.constraints.NotNull;

public record UpdateCategoryRequest(
        @NotNull TransactionCategory category
) {
}
