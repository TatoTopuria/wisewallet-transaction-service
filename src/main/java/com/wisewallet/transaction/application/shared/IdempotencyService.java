package com.wisewallet.transaction.application.shared;

import com.wisewallet.transaction.domain.exception.DuplicateIdempotencyKeyException;
import com.wisewallet.transaction.domain.model.IdempotencyKey;
import com.wisewallet.transaction.domain.repository.IdempotencyKeyRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepositoryPort idempotencyKeyRepository;

    @Value("${wisewallet.transaction.idempotency.ttl-hours:48}")
    private int ttlHours;

    public sealed interface IdempotencyResult permits IdempotencyResult.Proceed, IdempotencyResult.Cached {
        record Proceed() implements IdempotencyResult {}
        record Cached(int httpStatus, String responseBody) implements IdempotencyResult {}
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyResult checkOrInsert(String key, UUID userId) {
        var existing = idempotencyKeyRepository.findByKeyAndUserId(key, userId);
        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();
            if (record.getResponseBody() != null) {
                return new IdempotencyResult.Cached(
                        record.getResponseStatus(), record.getResponseBody());
            }
            throw new DuplicateIdempotencyKeyException(
                    "Request with Idempotency-Key '" + key + "' is already in progress");
        }

        try {
            IdempotencyKey record = IdempotencyKey.builder()
                    .id(UUID.randomUUID())
                    .key(key)
                    .userId(userId)
                    .expiresAt(Instant.now().plusSeconds((long) ttlHours * 3600))
                    .build();
            idempotencyKeyRepository.saveAndFlush(record);
            return new IdempotencyResult.Proceed();
        } catch (DataIntegrityViolationException e) {
            return idempotencyKeyRepository.findByKeyAndUserId(key, userId)
                    .map(rec -> {
                        if (rec.getResponseBody() != null) {
                            return (IdempotencyResult) new IdempotencyResult.Cached(
                                    rec.getResponseStatus(), rec.getResponseBody());
                        }
                        throw new DuplicateIdempotencyKeyException(
                                "Request with Idempotency-Key '" + key + "' is already in progress");
                    })
                    .orElseThrow(() -> new DuplicateIdempotencyKeyException(
                            "Duplicate idempotency key: " + key));
        }
    }

    @Transactional
    public void complete(String key, UUID userId, int httpStatus, String responseBody) {
        idempotencyKeyRepository.findByKeyAndUserId(key, userId).ifPresent(record -> {
            record.setResponseStatus(httpStatus);
            record.setResponseBody(responseBody);
            idempotencyKeyRepository.save(record);
        });
    }
}
