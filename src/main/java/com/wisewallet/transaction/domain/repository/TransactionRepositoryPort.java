package com.wisewallet.transaction.domain.repository;

import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepositoryPort {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    void flush();

    Page<Transaction> findByFilter(
            UUID userId,
            UUID accountId,
            Instant from,
            Instant to,
            List<TransactionCategory> categories,
            TransactionType type,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            TransactionStatus status,
            Pageable pageable
    );

    List<CategoryAggregation> aggregateByCategory(UUID userId, Instant from, Instant to);

    interface CategoryAggregation {
        TransactionCategory getCategory();
        BigDecimal getTotal();
        Long getTransactionCount();
    }
}
