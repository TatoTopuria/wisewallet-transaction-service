package com.wisewallet.transaction.domain.service;

import com.wisewallet.transaction.domain.model.TransactionCategory;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class MccCategoryMapping {

    private static final Map<String, TransactionCategory> MCC_MAP = Map.ofEntries(
            Map.entry("5411", TransactionCategory.GROCERIES),
            Map.entry("5412", TransactionCategory.GROCERIES),
            Map.entry("5422", TransactionCategory.GROCERIES),
            Map.entry("4900", TransactionCategory.UTILITIES),
            Map.entry("4911", TransactionCategory.UTILITIES),
            Map.entry("4941", TransactionCategory.UTILITIES),
            Map.entry("7832", TransactionCategory.ENTERTAINMENT),
            Map.entry("7922", TransactionCategory.ENTERTAINMENT),
            Map.entry("7929", TransactionCategory.ENTERTAINMENT),
            Map.entry("5812", TransactionCategory.DINING),
            Map.entry("5814", TransactionCategory.DINING),
            Map.entry("5811", TransactionCategory.DINING),
            Map.entry("4111", TransactionCategory.TRANSPORT),
            Map.entry("4121", TransactionCategory.TRANSPORT),
            Map.entry("4131", TransactionCategory.TRANSPORT),
            Map.entry("8011", TransactionCategory.HEALTHCARE),
            Map.entry("8021", TransactionCategory.HEALTHCARE),
            Map.entry("5912", TransactionCategory.HEALTHCARE),
            Map.entry("4511", TransactionCategory.TRAVEL),
            Map.entry("7011", TransactionCategory.TRAVEL),
            Map.entry("4112", TransactionCategory.TRAVEL),
            Map.entry("6012", TransactionCategory.INCOME),
            Map.entry("6051", TransactionCategory.INCOME),
            Map.entry("5350", TransactionCategory.SHOPPING),
            Map.entry("5399", TransactionCategory.SHOPPING),
            Map.entry("5691", TransactionCategory.SHOPPING)
    );

    public Optional<TransactionCategory> categorize(String mccCode) {
        if (mccCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MCC_MAP.get(mccCode));
    }
}
