package com.wisewallet.transaction.infrastructure.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DebitCreditRequest(BigDecimal amount, UUID transactionId) {
}
