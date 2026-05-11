package com.wisewallet.transaction.domain.model;

public enum TransactionStatus {
    PENDING,
    DEBITED,
    COMPLETED,
    FAILED,
    CANCELLED,
    COMPENSATION_PENDING
}
