package com.wisewallet.transaction.infrastructure.job;

import com.wisewallet.transaction.domain.repository.IdempotencyKeyRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyKeyRepositoryPort idempotencyKeyRepository;

    @Scheduled(cron = "${wisewallet.transaction.idempotency.cleanup-cron:0 0 * * * *}")
    @Transactional
    public void cleanup() {
        int deleted = idempotencyKeyRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired idempotency keys", deleted);
        }
    }
}
