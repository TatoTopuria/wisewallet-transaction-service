package com.wisewallet.transaction.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.infrastructure.messaging.event.TransferCompensationDomainEvent;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes compensation events and performs best-effort reversal:
 * <ol>
 *   <li>Releases the source reservation (idempotent — treats non-ACTIVE as success).</li>
 *   <li>Debits the destination account to reverse the already-applied credit.</li>
 * </ol>
 *
 * Failures throw so Spring-Kafka retries the message; after exhausting retries the
 * message is forwarded to the DLT by the configured DefaultErrorHandler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompensationEventConsumer {

    private final AccountServicePort accountServicePort;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${wisewallet.transaction.kafka.topics.txn-compensation:txn.compensation}",
            groupId = "${wisewallet.transaction.kafka.compensation-consumer-group:txn-compensation-group}",
            containerFactory = "compensationKafkaListenerContainerFactory"
    )
    public void handleCompensation(ConsumerRecord<String, String> record) {
        TransferCompensationDomainEvent event;
        try {
            event = objectMapper.readValue(record.value(), TransferCompensationDomainEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialize compensation event. offset={}, key={}",
                    record.offset(), record.key(), e);
            // Non-retryable deserialization failure — do not rethrow so DLT receives it via error handler
            throw new IllegalArgumentException("Unreadable compensation event at offset " + record.offset(), e);
        }

        log.info("Processing compensation event. transferId={}, reservationId={}",
                event.transferId(), event.reservationId());

        releaseSourceReservation(event);
        debitDestinationAccount(event);

        log.info("Compensation completed successfully. transferId={}", event.transferId());
    }

    private void releaseSourceReservation(TransferCompensationDomainEvent event) {
        try {
            accountServicePort.release(event.sourceAccountId(), event.reservationId(), event.currency());
        } catch (FeignException.UnprocessableEntity e) {
            // Reservation already released or committed — treat as idempotent success
            log.warn("Release returned 422 (reservation not ACTIVE) — treating as already compensated. " +
                    "transferId={}, reservationId={}", event.transferId(), event.reservationId());
        } catch (Exception e) {
            log.error("Failed to release reservation during compensation. transferId={}, reservationId={}",
                    event.transferId(), event.reservationId(), e);
            throw e; // triggers Kafka retry
        }
    }

    private void debitDestinationAccount(TransferCompensationDomainEvent event) {
        // Deterministic compensationTxnId ensures idempotent debit if consumer retries
        UUID compensationTxnId = event.creditTransactionId() != null
                ? UUID.nameUUIDFromBytes(("debit-reverse:" + event.creditTransactionId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : UUID.nameUUIDFromBytes(("debit-reverse:" + event.transferId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            accountServicePort.debit(event.destinationAccountId(), event.amount(),
                    event.currency(), compensationTxnId);
        } catch (FeignException.UnprocessableEntity e) {
            // Destination may already have been debited (idempotent debit hit constraint)
            log.warn("Debit reversal returned 422 — treating as already reversed. transferId={}",
                    event.transferId());
        } catch (Exception e) {
            log.error("Failed to debit destination during compensation. transferId={}, destinationAccountId={}",
                    event.transferId(), event.destinationAccountId(), e);
            throw e; // triggers Kafka retry
        }
    }
}
