package com.wisewallet.transaction.infrastructure.persistence.spec;

import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TransactionSpecification {

    private TransactionSpecification() {
    }

    public static Specification<Transaction> forUser(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<Transaction> notDeleted() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<Transaction> withFilters(
            UUID userId,
            UUID accountId,
            Instant from,
            Instant to,
            List<TransactionCategory> categories,
            TransactionType type,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            TransactionStatus status) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.isFalse(root.get("deleted")));

            if (accountId != null) {
                predicates.add(cb.equal(root.get("accountId"), accountId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (categories != null && !categories.isEmpty()) {
                predicates.add(root.get("category").in(categories));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (minAmount != null) {
                predicates.add(cb.greaterThanOrEqualTo(cb.abs(root.get("amount")), minAmount));
            }
            if (maxAmount != null) {
                predicates.add(cb.lessThanOrEqualTo(cb.abs(root.get("amount")), maxAmount));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public static Specification<Transaction> amountAbsSort() {
        return (root, query, cb) -> {
            if (query != null) {
                query.orderBy(cb.asc(cb.abs(root.get("amount"))));
            }
            return cb.conjunction();
        };
    }
}
