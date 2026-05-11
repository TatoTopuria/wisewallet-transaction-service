package com.wisewallet.transaction.repository;

import com.wisewallet.transaction.domain.model.*;
import com.wisewallet.transaction.infrastructure.persistence.TransactionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.wisewallet.transaction.infrastructure.persistence.spec.TransactionSpecification.withFilters;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TransactionJpaRepository transactionRepository;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private Transaction save(BigDecimal amount, TransactionType type, TransactionStatus status,
                              TransactionCategory category) {
        return transactionRepository.save(Transaction.builder()
                .userId(userId)
                .accountId(accountId)
                .amount(amount)
                .currency("USD")
                .type(type)
                .status(status)
                .category(category)
                .idempotencyKey(UUID.randomUUID().toString())
                .build());
    }

    private Transaction saveForAccount(UUID targetAccount, BigDecimal amount) {
        return transactionRepository.save(Transaction.builder()
                .userId(userId)
                .accountId(targetAccount)
                .amount(amount)
                .currency("USD")
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.COMPLETED)
                .category(TransactionCategory.OTHER)
                .idempotencyKey(UUID.randomUUID().toString())
                .build());
    }

    // ─── filter tests ──────────────────────────────────────────────────────────

    @Test
    void filter_byAccountId_returnsOnlyMatchingAccount() {
        var other = UUID.randomUUID();
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);
        saveForAccount(other, BigDecimal.TEN);

        var spec = withFilters(userId, accountId, null, null, null, null, null, null, null);
        var results = transactionRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAccountId()).isEqualTo(accountId);
    }

    @Test
    void filter_byDateRange_returnsOnlyWithinRange() {
        var now = Instant.now();
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);

        var from = now.minus(1, ChronoUnit.MINUTES);
        var to = now.plus(1, ChronoUnit.MINUTES);
        var spec = withFilters(userId, null, from, to, null, null, null, null, null);
        var results = transactionRepository.findAll(spec);

        assertThat(results).hasSize(1);
    }

    @Test
    void filter_byDateRange_excludesOutsideRange() {
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);

        var from = Instant.now().plus(1, ChronoUnit.HOURS);
        var to = Instant.now().plus(2, ChronoUnit.HOURS);
        var spec = withFilters(userId, null, from, to, null, null, null, null, null);
        var results = transactionRepository.findAll(spec);

        assertThat(results).isEmpty();
    }

    @Test
    void filter_byCategory_returnsMatchingRows() {
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.GROCERIES);
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.DINING);
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);

        var spec = withFilters(userId, null, null, null,
                List.of(TransactionCategory.GROCERIES, TransactionCategory.DINING),
                null, null, null, null);
        var results = transactionRepository.findAll(spec);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Transaction::getCategory)
                .containsExactlyInAnyOrder(TransactionCategory.GROCERIES, TransactionCategory.DINING);
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void filter_byType_returnsOnlyMatchingType(TransactionType type) {
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);
        save(new BigDecimal("-10"), TransactionType.WITHDRAWAL, TransactionStatus.COMPLETED, TransactionCategory.OTHER);

        var spec = withFilters(userId, null, null, null, null, type, null, null, null);
        var results = transactionRepository.findAll(spec);

        assertThat(results).allMatch(t -> t.getType() == type);
    }

    @Test
    void filter_byStatus_returnsOnlyMatchingStatus() {
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.PENDING, TransactionCategory.OTHER);

        var spec = withFilters(userId, null, null, null, null, null, null, null, TransactionStatus.COMPLETED);
        var results = transactionRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void filter_deletedTransactions_areExcluded() {
        var tx = save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);
        tx.setDeleted(true);
        transactionRepository.save(tx);

        var spec = withFilters(userId, null, null, null, null, null, null, null, null);
        var results = transactionRepository.findAll(spec);

        assertThat(results).isEmpty();
    }

    @Test
    void filter_combined_appliesAllPredicates() {
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.GROCERIES);
        save(BigDecimal.TEN, TransactionType.WITHDRAWAL, TransactionStatus.COMPLETED, TransactionCategory.GROCERIES);
        save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.PENDING, TransactionCategory.GROCERIES);

        var spec = withFilters(userId, accountId, null, null,
                List.of(TransactionCategory.GROCERIES),
                TransactionType.DEPOSIT, null, null, TransactionStatus.COMPLETED);
        var results = transactionRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(results.get(0).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    // ─── pagination ────────────────────────────────────────────────────────────

    @Test
    void pagination_returnsCorrectPage() {
        for (int i = 0; i < 5; i++) {
            save(BigDecimal.TEN, TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.OTHER);
        }

        var spec = withFilters(userId, null, null, null, null, null, null, null, null);
        var page = transactionRepository.findAll(spec, PageRequest.of(0, 3, Sort.by("createdAt").descending()));

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    // ─── monthly aggregation ───────────────────────────────────────────────────

    @Test
    void aggregateByCategory_returnsCorrectSumsPerCategory() {
        var now = Instant.now();
        var from = now.minus(1, ChronoUnit.MINUTES);
        var to = now.plus(1, ChronoUnit.MINUTES);

        save(new BigDecimal("100"), TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.GROCERIES);
        save(new BigDecimal("50"), TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.GROCERIES);
        save(new BigDecimal("200"), TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.DINING);
        // Should be excluded — PENDING status
        save(new BigDecimal("999"), TransactionType.DEPOSIT, TransactionStatus.PENDING, TransactionCategory.GROCERIES);

        var results = transactionRepository.aggregateByCategory(userId, from, to);

        assertThat(results).hasSize(2);

        var groceries = results.stream()
                .filter(r -> r.getCategory() == TransactionCategory.GROCERIES)
                .findFirst().orElseThrow();
        assertThat(groceries.getTotal()).isEqualByComparingTo("150");
        assertThat(groceries.getTransactionCount()).isEqualTo(2);

        var dining = results.stream()
                .filter(r -> r.getCategory() == TransactionCategory.DINING)
                .findFirst().orElseThrow();
        assertThat(dining.getTotal()).isEqualByComparingTo("200");
        assertThat(dining.getTransactionCount()).isEqualTo(1);
    }

    @Test
    void aggregateByCategory_excludesDeletedTransactions() {
        var now = Instant.now();
        var from = now.minus(1, ChronoUnit.MINUTES);
        var to = now.plus(1, ChronoUnit.MINUTES);

        var tx = save(new BigDecimal("100"), TransactionType.DEPOSIT, TransactionStatus.COMPLETED, TransactionCategory.GROCERIES);
        tx.setDeleted(true);
        transactionRepository.save(tx);

        var results = transactionRepository.aggregateByCategory(userId, from, to);
        assertThat(results).isEmpty();
    }

    @Test
    void aggregateByCategory_excludesOtherUsers() {
        var now = Instant.now();
        var from = now.minus(1, ChronoUnit.MINUTES);
        var to = now.plus(1, ChronoUnit.MINUTES);

        // Save for a different userId
        transactionRepository.save(Transaction.builder()
                .userId(UUID.randomUUID())
                .accountId(accountId)
                .amount(new BigDecimal("100"))
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.COMPLETED)
                .category(TransactionCategory.GROCERIES)
                .idempotencyKey(UUID.randomUUID().toString())
                .build());

        var results = transactionRepository.aggregateByCategory(userId, from, to);
        assertThat(results).isEmpty();
    }
}
