package com.wisewallet.transaction.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.domain.model.OutboxEvent;
import com.wisewallet.transaction.domain.repository.OutboxEventRepositoryPort;
import com.wisewallet.transaction.infrastructure.messaging.event.TransferCompensationDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Schedules a compensation event into the outbox using REQUIRES_NEW propagation,
 * ensuring the outbox row is committed even if the calling transaction rolls back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompensationOutboxService {

    private static final String EVENT_TYPE = "txn.compensation.needed";

    private final OutboxEventRepositoryPort outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persists a compensation outbox event that will be published to Kafka by the
     * outbox poller. Runs in its own transaction (REQUIRES_NEW) so it survives a
     * parent transaction rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleCompensation(UUID transferId,
                                     UUID reservationId,
                                     UUID sourceAccountId,
                                     UUID destinationAccountId,
                                     BigDecimal amount,
                                     String currency,
                                     UUID creditTransactionId) {
        // Deterministic ID: same transferId always produces the same compensation event UUID,
        // making repeated scheduling idempotent.
        UUID compensationId = UUID.nameUUIDFromBytes(
                ("compensation:" + transferId).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        TransferCompensationDomainEvent payload = new TransferCompensationDomainEvent(
                transferId, reservationId, sourceAccountId, destinationAccountId,
                amount, currency, creditTransactionId);

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .id(compensationId)
                    .aggregateId(transferId)
                    .eventType(EVENT_TYPE)
                    .payload(payloadJson)
                    .status("PENDING")
                    .build();
            outboxEventRepository.save(event);
            log.info("Scheduled compensation outbox event. compensationId={}, transferId={}",
                    compensationId, transferId);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to schedule compensation for transferId={}. Manual intervention required.",
                    transferId, e);
            throw new IllegalStateException("Could not schedule compensation", e);
        }
    }
}
