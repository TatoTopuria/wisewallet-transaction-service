package com.wisewallet.transaction.application.query;

import com.wisewallet.transaction.domain.exception.BusinessRuleException;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort.CategoryAggregation;
import com.wisewallet.transaction.presentation.dto.response.CategorySummary;
import com.wisewallet.transaction.presentation.dto.response.MonthlySummaryResponse;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepositoryPort transactionRepository;
    private final TransactionMapper transactionMapper;

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(
            UUID userId,
            UUID accountId,
            Instant from,
            Instant to,
            List<TransactionCategory> categories,
            TransactionType type,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            TransactionStatus status,
            Pageable pageable) {

        return transactionRepository
                .findByFilter(userId, accountId, from, to, categories, type, minAmount, maxAmount, status, pageable)
                .map(transactionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MonthlySummaryResponse monthlySummary(UUID userId, int year, int month) {
        validateHistoricalDepth(year, month);

        YearMonth yearMonth = YearMonth.of(year, month);
        Instant from = yearMonth.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = yearMonth.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

        List<CategoryAggregation> aggregations = transactionRepository.aggregateByCategory(userId, from, to);

        BigDecimal totalSpent = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;

        List<CategorySummary> byCategory = aggregations.stream()
                .map(a -> new CategorySummary(a.getCategory(), a.getTotal(), a.getTransactionCount()))
                .toList();

        for (CategoryAggregation agg : aggregations) {
            if (agg.getTotal().compareTo(BigDecimal.ZERO) < 0) {
                totalSpent = totalSpent.add(agg.getTotal());
            } else {
                totalIncome = totalIncome.add(agg.getTotal());
            }
        }

        BigDecimal netFlow = totalSpent.add(totalIncome);

        return new MonthlySummaryResponse(year, month, totalSpent, totalIncome, netFlow, byCategory);
    }

    private void validateHistoricalDepth(int year, int month) {
        YearMonth requested = YearMonth.of(year, month);
        YearMonth oldest = YearMonth.now(ZoneOffset.UTC).minusMonths(12);
        YearMonth current = YearMonth.now(ZoneOffset.UTC);

        if (requested.isBefore(oldest) || requested.isAfter(current)) {
            throw new BusinessRuleException(
                    "Requested month is outside the allowed range (last 12 months)");
        }
    }
}
