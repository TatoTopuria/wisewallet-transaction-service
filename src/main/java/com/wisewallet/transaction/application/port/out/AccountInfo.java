package com.wisewallet.transaction.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Application-layer representation of a remote account.
 * Produced by AccountServicePort.getAccount().
 */
public record AccountInfo(
        UUID id,
        UUID userId,
        String accountType,
        String status,
        List<String> availableCurrencies
) {
}
