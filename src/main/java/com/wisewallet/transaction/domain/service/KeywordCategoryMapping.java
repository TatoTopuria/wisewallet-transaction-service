package com.wisewallet.transaction.domain.service;

import com.wisewallet.transaction.domain.model.TransactionCategory;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class KeywordCategoryMapping {

    private static final Map<String, TransactionCategory> KEYWORD_MAP = Map.ofEntries(
            Map.entry("salary", TransactionCategory.INCOME),
            Map.entry("payroll", TransactionCategory.INCOME),
            Map.entry("dividend", TransactionCategory.INCOME),
            Map.entry("rent", TransactionCategory.HOUSING),
            Map.entry("mortgage", TransactionCategory.HOUSING),
            Map.entry("electricity", TransactionCategory.UTILITIES),
            Map.entry("water bill", TransactionCategory.UTILITIES),
            Map.entry("gas bill", TransactionCategory.UTILITIES),
            Map.entry("insurance", TransactionCategory.INSURANCE),
            Map.entry("gym", TransactionCategory.HEALTH_FITNESS),
            Map.entry("fitness", TransactionCategory.HEALTH_FITNESS),
            Map.entry("education", TransactionCategory.EDUCATION),
            Map.entry("tuition", TransactionCategory.EDUCATION),
            Map.entry("school", TransactionCategory.EDUCATION),
            Map.entry("restaurant", TransactionCategory.DINING),
            Map.entry("cafe", TransactionCategory.DINING),
            Map.entry("grocery", TransactionCategory.GROCERIES),
            Map.entry("supermarket", TransactionCategory.GROCERIES),
            Map.entry("amazon", TransactionCategory.SHOPPING),
            Map.entry("subscription", TransactionCategory.SUBSCRIPTIONS),
            Map.entry("netflix", TransactionCategory.SUBSCRIPTIONS),
            Map.entry("spotify", TransactionCategory.SUBSCRIPTIONS)
    );

    public Optional<TransactionCategory> categorize(String description) {
        if (description == null) {
            return Optional.empty();
        }
        String lower = description.toLowerCase();
        return KEYWORD_MAP.entrySet().stream()
                .filter(e -> lower.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
