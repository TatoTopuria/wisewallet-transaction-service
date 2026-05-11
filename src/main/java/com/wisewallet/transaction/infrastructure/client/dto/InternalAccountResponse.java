package com.wisewallet.transaction.infrastructure.client.dto;

import java.util.List;
import java.util.UUID;

public record InternalAccountResponse(
        UUID id,
        UUID userId,
        String accountType,
        String status,
        List<String> availableCurrencies
) {
}
