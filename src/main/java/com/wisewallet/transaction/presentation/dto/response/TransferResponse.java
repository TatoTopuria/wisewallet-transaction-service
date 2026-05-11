package com.wisewallet.transaction.presentation.dto.response;

import java.util.List;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        List<TransactionResponse> transactions
) {
}
