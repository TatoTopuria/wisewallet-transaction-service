package com.wisewallet.transaction.service;

import com.wisewallet.transaction.application.shared.IdempotencyService;
import com.wisewallet.transaction.domain.exception.DuplicateIdempotencyKeyException;
import com.wisewallet.transaction.domain.model.IdempotencyKey;
import com.wisewallet.transaction.domain.repository.IdempotencyKeyRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock IdempotencyKeyRepositoryPort idempotencyKeyRepository;

    @InjectMocks IdempotencyService idempotencyService;

    private UUID userId;
    private String key;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        key = UUID.randomUUID().toString();
    }

    @Test
    void checkOrInsert_freshKey_returnsProceed() {
        when(idempotencyKeyRepository.saveAndFlush(any(IdempotencyKey.class)))
                .thenReturn(mock(IdempotencyKey.class));

        var result = idempotencyService.checkOrInsert(key, userId);

        assertThat(result).isInstanceOf(IdempotencyService.IdempotencyResult.Proceed.class);
    }

    @Test
    void checkOrInsert_completedKey_returnsCachedResponse() {
        when(idempotencyKeyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        var existing = IdempotencyKey.builder()
                .id(UUID.randomUUID())
                .key(key)
                .userId(userId)
                .responseStatus(201)
                .responseBody("{\"id\":\"abc\"}")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(idempotencyKeyRepository.findByKeyAndUserId(key, userId)).thenReturn(Optional.of(existing));

        var result = idempotencyService.checkOrInsert(key, userId);

        assertThat(result).isInstanceOf(IdempotencyService.IdempotencyResult.Cached.class);
        var cached = (IdempotencyService.IdempotencyResult.Cached) result;
        assertThat(cached.httpStatus()).isEqualTo(201);
        assertThat(cached.responseBody()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    void checkOrInsert_inFlightKey_throws409() {
        when(idempotencyKeyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        var inFlight = IdempotencyKey.builder()
                .id(UUID.randomUUID())
                .key(key)
                .userId(userId)
                .responseBody(null) // null = in-flight
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(idempotencyKeyRepository.findByKeyAndUserId(key, userId)).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> idempotencyService.checkOrInsert(key, userId))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
    }
}
