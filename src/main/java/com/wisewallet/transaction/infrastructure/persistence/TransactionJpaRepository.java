package com.wisewallet.transaction.infrastructure.persistence;

import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.infrastructure.persistence.spec.TransactionSpecification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionJpaRepository
        extends JpaRepository<Transaction, UUID>,
                JpaSpecificationExecutor<Transaction> {

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS total,
                   COUNT(t) AS transactionCount
            FROM Transaction t
            WHERE t.userId = :userId
              AND t.status = com.wisewallet.transaction.domain.model.TransactionStatus.COMPLETED
              AND t.deleted = false
              AND t.createdAt >= :from
              AND t.createdAt < :to
            GROUP BY t.category
            """)
    List<TransactionRepositoryPort.CategoryAggregation> aggregateByCategory(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    default Page<Transaction> findByFilter(
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

        return findAll(
                TransactionSpecification.withFilters(
                        userId, accountId, from, to, categories, type, minAmount, maxAmount, status),
                pageable);
    }
}
