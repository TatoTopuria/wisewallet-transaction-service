package com.wisewallet.transaction.infrastructure.client.dto;

import java.util.UUID;

public record CommitReleaseRequest(UUID reservationId) {
}
