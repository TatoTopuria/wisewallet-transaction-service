package com.wisewallet.transaction.categorization;

import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.service.CategoryRuleEngine;
import com.wisewallet.transaction.domain.service.KeywordCategoryMapping;
import com.wisewallet.transaction.domain.service.MccCategoryMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CategoryRuleEngineTest {

    private KeywordCategoryMapping keywordCategoryMapping;
    private CategoryRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        keywordCategoryMapping = mock(KeywordCategoryMapping.class);
        when(keywordCategoryMapping.categorize(any())).thenReturn(Optional.empty());
        ruleEngine = new CategoryRuleEngine(new MccCategoryMapping(), keywordCategoryMapping);
    }

    @Test
    void transferType_alwaysReturnsTRANSFER_category() {
        var result = ruleEngine.categorize(TransactionType.TRANSFER, "5411", null);
        assertThat(result).isEqualTo(TransactionCategory.TRANSFER);
        verifyNoInteractions(keywordCategoryMapping);
    }

    @ParameterizedTest
    @CsvSource({
            "5411, GROCERIES",
            "5412, GROCERIES",
            "4900, UTILITIES",
            "7832, ENTERTAINMENT",
            "5812, DINING",
            "4111, TRANSPORT",
            "8011, HEALTHCARE",
            "4511, TRAVEL",
            "6012, INCOME",
            "5350, SHOPPING",
            "5399, SHOPPING"
    })
    void mccCode_matchesExpectedCategory(String mcc, String expectedCategory) {
        var result = ruleEngine.categorize(TransactionType.DEPOSIT, mcc, null);
        assertThat(result).isEqualTo(TransactionCategory.valueOf(expectedCategory));
    }

    @Test
    void unknownMcc_fallsThrough_toOTHER() {
        var result = ruleEngine.categorize(TransactionType.WITHDRAWAL, "9999", null);
        assertThat(result).isEqualTo(TransactionCategory.OTHER);
    }

    @Test
    void nullMcc_noKeywordMatch_returnsOTHER() {
        var result = ruleEngine.categorize(TransactionType.DEPOSIT, null, null);
        assertThat(result).isEqualTo(TransactionCategory.OTHER);
    }
}
