package com.wisewallet.transaction.domain.service;

import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CategoryRuleEngine {

    private final MccCategoryMapping mccCategoryMapping;
    private final KeywordCategoryMapping keywordCategoryMapping;

    public TransactionCategory categorize(TransactionType type, String mccCode, String description) {
        if (type == TransactionType.TRANSFER) {
            return TransactionCategory.TRANSFER;
        }

        return mccCategoryMapping.categorize(mccCode)
                .or(() -> keywordCategoryMapping.categorize(description))
                .orElse(TransactionCategory.OTHER);
    }
}
