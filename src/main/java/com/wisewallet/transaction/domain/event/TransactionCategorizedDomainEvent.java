package com.wisewallet.transaction.domain.event;

import com.wisewallet.transaction.domain.model.Transaction;

public record TransactionCategorizedDomainEvent(Transaction transaction) {}
