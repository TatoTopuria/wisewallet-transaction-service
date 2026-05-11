package com.wisewallet.transaction.repository;

import com.wisewallet.transaction.domain.model.IdempotencyKey;
import com.wisewallet.transaction.infrastructure.persistence.IdempotencyKeyJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyKeyRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    IdempotencyKeyJpaRepository idempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
    }

    private IdempotencyKey buildKey(String key, UUID userId, Instant expiresAt) {
        return IdempotencyKey.builder()
                .id(UUID.randomUUID())
                .key(key)
                .userId(userId)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void findByKeyAndUserId_matchingRecord_returnsPresent() {
        var userId = UUID.randomUUID();
        var key = "test-key-" + UUID.randomUUID();
        idempotencyKeyRepository.save(buildKey(key, userId, Instant.now().plus(48, ChronoUnit.HOURS)));

        var result = idempotencyKeyRepository.findByKeyAndUserId(key, userId);

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isEqualTo(key);
        assertThat(result.get().getUserId()).isEqualTo(userId);
    }

    @Test
    void findByKeyAndUserId_differentUser_returnsEmpty() {
        var userId = UUID.randomUUID();
        var otherUserId = UUID.randomUUID();
        var key = "test-key-" + UUID.randomUUID();
        idempotencyKeyRepository.save(buildKey(key, userId, Instant.now().plus(48, ChronoUnit.HOURS)));

        var result = idempotencyKeyRepository.findByKeyAndUserId(key, otherUserId);

        assertThat(result).isEmpty();
    }

    @Test
    void findByKeyAndUserId_differentKey_returnsEmpty() {
        var userId = UUID.randomUUID();
        idempotencyKeyRepository.save(buildKey("key-A", userId, Instant.now().plus(48, ChronoUnit.HOURS)));

        var result = idempotencyKeyRepository.findByKeyAndUserId("key-B", userId);

        assertThat(result).isEmpty();
    }

    @Test
    void uniqueConstraint_sameKeyAndUser_throwsOnDuplicate() {
        var userId = UUID.randomUUID();
        var key = "unique-key-" + UUID.randomUUID();

        idempotencyKeyRepository.save(buildKey(key, userId, Instant.now().plus(48, ChronoUnit.HOURS)));
        idempotencyKeyRepository.flush();

        assertThatThrownBy(() -> {
            idempotencyKeyRepository.save(buildKey(key, userId, Instant.now().plus(48, ChronoUnit.HOURS)));
            idempotencyKeyRepository.flush();
        }).isInstanceOf(Exception.class); // DataIntegrityViolationException or PersistenceException
    }

    @Test
    void uniqueConstraint_sameKeySameUser_allowsDifferentUsers() {
        var user1 = UUID.randomUUID();
        var user2 = UUID.randomUUID();
        var key = "shared-key-" + UUID.randomUUID();

        idempotencyKeyRepository.save(buildKey(key, user1, Instant.now().plus(48, ChronoUnit.HOURS)));
        idempotencyKeyRepository.save(buildKey(key, user2, Instant.now().plus(48, ChronoUnit.HOURS)));
        idempotencyKeyRepository.flush();

        assertThat(idempotencyKeyRepository.count()).isEqualTo(2);
    }

    @Test
    void deleteExpired_removesOnlyExpiredKeys() {
        var userId = UUID.randomUUID();
        var expiredKey = buildKey("expired-" + UUID.randomUUID(), userId,
                Instant.now().minus(1, ChronoUnit.HOURS));
        var validKey = buildKey("valid-" + UUID.randomUUID(), userId,
                Instant.now().plus(48, ChronoUnit.HOURS));

        idempotencyKeyRepository.save(expiredKey);
        idempotencyKeyRepository.save(validKey);
        idempotencyKeyRepository.flush();

        int deleted = idempotencyKeyRepository.deleteExpired(Instant.now());

        assertThat(deleted).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);

        var remaining = idempotencyKeyRepository.findAll().get(0);
        assertThat(remaining.getKey()).isEqualTo(validKey.getKey());
    }

    @Test
    void deleteExpired_nothingExpired_deletesNothing() {
        var userId = UUID.randomUUID();
        idempotencyKeyRepository.save(buildKey("key-" + UUID.randomUUID(), userId,
                Instant.now().plus(48, ChronoUnit.HOURS)));

        int deleted = idempotencyKeyRepository.deleteExpired(Instant.now());

        assertThat(deleted).isZero();
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }
}
