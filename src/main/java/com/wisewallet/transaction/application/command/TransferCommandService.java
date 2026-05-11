package com.wisewallet.transaction.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.application.port.out.AccountServicePort;
import com.wisewallet.transaction.application.port.out.ReservationResult;
import com.wisewallet.transaction.application.shared.IdempotencyService;
import com.wisewallet.transaction.application.shared.IdempotencyService.IdempotencyResult;
import com.wisewallet.transaction.domain.event.TransactionCreatedDomainEvent;
import com.wisewallet.transaction.domain.exception.BusinessRuleException;
import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.presentation.dto.request.TransferRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.dto.response.TransferResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import feign.FeignException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferCommandService {

    private static final Logger log = LoggerFactory.getLogger(TransferCommandService.class);

    private final TransactionRepositoryPort transactionRepository;
    private final AccountServicePort accountServicePort;
    private final IdempotencyService idempotencyService;
    private final TransactionMapper transactionMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Transactional
    public TransferResponse transfer(UUID userId, String idempotencyKey, TransferRequest request) {
        IdempotencyResult result = idempotencyService.checkOrInsert(idempotencyKey, userId);

        if (result instanceof IdempotencyResult.Cached cached) {
            try {
                return objectMapper.readValue(cached.responseBody(), TransferResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
            }
        }

        return executeSaga(userId, idempotencyKey, request);
    }

    protected TransferResponse executeSaga(UUID userId, String idempotencyKey, TransferRequest request) {
        // Phase 1 — Setup [TX1]
        UUID transferId = UUID.randomUUID();
        Transaction[] legs = setupLegs(userId, idempotencyKey, request, transferId);
        Transaction debitTxn = legs[0];
        Transaction creditTxn = legs[1];

        // Phase 2 — Reserve [Feign]
        ReservationResult reservation;
        try {
            reservation = accountServicePort.reserve(
                    request.sourceAccountId(), request.amount(), request.currency(), debitTxn.getId());
        } catch (FeignException.UnprocessableEntity e) {
            failBothLegs(debitTxn, creditTxn, idempotencyKey, userId,
                    "Insufficient available balance or source account is not ACTIVE", 422);
            throw new BusinessRuleException("Insufficient available balance or source account is not ACTIVE");
        } catch (FeignException e) {
            failBothLegs(debitTxn, creditTxn, idempotencyKey, userId,
                    "Account service error during reservation", 422);
            throw new BusinessRuleException("Account service error during reservation: " + e.getMessage());
        }

        // Phase 3 — Mark DEBITED [TX2]
        markDebited(debitTxn, reservation.reservationId());

        // Phase 4 — Credit destination [Feign]
        try {
            accountServicePort.credit(request.destinationAccountId(), request.amount(), request.currency(), creditTxn.getId());
        } catch (FeignException e) {
            log.warn("Credit to destination {} failed, initiating rollback. transferId={}",
                    request.destinationAccountId(), transferId);
            rollback(debitTxn, creditTxn, reservation.reservationId(),
                    request.sourceAccountId(), request.currency(), idempotencyKey, userId, e.getMessage());
            throw new BusinessRuleException("Destination account cannot be credited: " + e.getMessage());
        }

        // Phase 5 — Commit reservation [Feign]
        try {
            accountServicePort.commit(request.sourceAccountId(), reservation.reservationId(), request.currency());
        } catch (Exception e) {
            // CRITICAL: destination credited but reservation not committed
            log.error(
                    "CRITICAL: Transfer commit failed after credit succeeded. " +
                    "transferId={}, reservationId={}, sourceAccountId={}, destinationAccountId={}. " +
                    "Manual investigation required. Reservation will auto-expire in 10 minutes.",
                    transferId, reservation.reservationId(),
                    request.sourceAccountId(), request.destinationAccountId(), e);
            meterRegistry.counter("transfer.commit.failure.count").increment();
            throw new BusinessRuleException(
                    "Transfer commit failed after credit. Contact support with transferId: " + transferId);
        }

        // Phase 6 — Complete [TX3]
        return completeTransfer(debitTxn, creditTxn, transferId, idempotencyKey, userId);
    }

    protected Transaction[] setupLegs(UUID userId, String idempotencyKey,
                                       TransferRequest request, UUID transferId) {
        Transaction debitTxn = Transaction.builder()
                .userId(userId)
                .accountId(request.sourceAccountId())
                .transferId(transferId)
                .amount(request.amount().negate())
                .currency(request.currency())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .category(TransactionCategory.TRANSFER)
                .idempotencyKey(idempotencyKey)
                .build();

        Transaction creditTxn = Transaction.builder()
                .userId(userId)
                .accountId(request.destinationAccountId())
                .transferId(transferId)
                .amount(request.amount())
                .currency(request.currency())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .category(TransactionCategory.TRANSFER)
                .idempotencyKey(idempotencyKey)
                .build();

        transactionRepository.save(debitTxn);
        transactionRepository.save(creditTxn);
        transactionRepository.flush();

        return new Transaction[]{debitTxn, creditTxn};
    }

    protected void markDebited(Transaction debitTxn, UUID reservationId) {
        debitTxn.setStatus(TransactionStatus.DEBITED);
        debitTxn.setReservationId(reservationId);
        transactionRepository.save(debitTxn);
    }

    protected TransferResponse completeTransfer(Transaction debitTxn, Transaction creditTxn,
                                                UUID transferId, String idempotencyKey, UUID userId) {
        debitTxn.setStatus(TransactionStatus.COMPLETED);
        creditTxn.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(debitTxn);
        transactionRepository.save(creditTxn);

        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(debitTxn));
        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(creditTxn));

        List<TransactionResponse> responses = List.of(
                transactionMapper.toResponse(debitTxn),
                transactionMapper.toResponse(creditTxn)
        );
        TransferResponse response = new TransferResponse(transferId, responses);

        try {
            idempotencyService.complete(idempotencyKey, userId, 201, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("Failed to store idempotency response for transfer key {}", idempotencyKey, e);
        }

        return response;
    }

    protected void rollback(Transaction debitTxn, Transaction creditTxn, UUID reservationId,
                            UUID sourceAccountId, String currency, String idempotencyKey, UUID userId, String reason) {
        try {
            accountServicePort.release(sourceAccountId, reservationId, currency);
        } catch (Exception e) {
            log.error("Failed to release reservation {} during rollback. Manual action required. reason={}",
                    reservationId, reason, e);
        }

        debitTxn.setStatus(TransactionStatus.FAILED);
        creditTxn.setStatus(TransactionStatus.FAILED);
        transactionRepository.save(debitTxn);
        transactionRepository.save(creditTxn);

        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(debitTxn));
        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(creditTxn));

        try {
            var failResult = new TransferResponse(debitTxn.getTransferId(), List.of(
                    transactionMapper.toResponse(debitTxn),
                    transactionMapper.toResponse(creditTxn)
            ));
            idempotencyService.complete(idempotencyKey, userId, 422, objectMapper.writeValueAsString(failResult));
        } catch (Exception e) {
            log.warn("Failed to store failure idempotency response for key {}", idempotencyKey, e);
        }
    }

    protected void failBothLegs(Transaction debitTxn, Transaction creditTxn,
                                String idempotencyKey, UUID userId, String reason, int statusCode) {
        debitTxn.setStatus(TransactionStatus.FAILED);
        creditTxn.setStatus(TransactionStatus.FAILED);
        transactionRepository.save(debitTxn);
        transactionRepository.save(creditTxn);

        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(debitTxn));
        eventPublisher.publishEvent(new TransactionCreatedDomainEvent(creditTxn));

        try {
            idempotencyService.complete(idempotencyKey, userId, statusCode,
                    objectMapper.writeValueAsString(Map.of("error", reason)));
        } catch (Exception e) {
            log.warn("Failed to store failure idempotency response for key {}", idempotencyKey, e);
        }
    }
}
