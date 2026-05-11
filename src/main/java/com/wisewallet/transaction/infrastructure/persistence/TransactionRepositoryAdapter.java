package com.wisewallet.transaction.infrastructure.persistence;

import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TransactionRepositoryAdapter implements TransactionRepositoryPort {

    private final TransactionJpaRepository jpaRepository;

    @Override
    public Transaction save(Transaction transaction) {
        return jpaRepository.save(transaction);
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    @Override
    public Page<Transaction> findByFilter(UUID userId, UUID accountId, Instant from, Instant to,
                                          List<TransactionCategory> categories, TransactionType type,
                                          BigDecimal minAmount, BigDecimal maxAmount,
                                          TransactionStatus status, Pageable pageable) {
        return jpaRepository.findByFilter(userId, accountId, from, to, categories, type, minAmount, maxAmount, status, pageable);
    }

    @Override
    public List<CategoryAggregation> aggregateByCategory(UUID userId, Instant from, Instant to) {
        return jpaRepository.aggregateByCategory(userId, from, to);
    }
}
