package com.wisewallet.transaction.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.domain.model.OutboxEvent;
import com.wisewallet.transaction.domain.repository.OutboxEventRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepositoryPort outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${wisewallet.transaction.outbox.batch-size:50}")
    private int batchSize;

    @Value("${wisewallet.transaction.outbox.max-retries:5}")
    private int maxRetries;

    @Value("${wisewallet.transaction.kafka.topics.txn-created:txn.created}")
    private String txnCreatedTopic;

    @Value("${wisewallet.transaction.kafka.topics.txn-categorized:txn.categorized}")
    private String txnCategorizedTopic;

    @Value("${wisewallet.transaction.kafka.topics.txn-compensation:txn.compensation}")
    private String txnCompensationTopic;

    @Scheduled(fixedDelayString = "${wisewallet.transaction.outbox.poll-fixed-delay-ms:2000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingForUpdate(batchSize);
        for (OutboxEvent event : pending) {
            try {
                String topic = resolveTopic(event.getEventType());
                String key = resolveKey(event);
                kafkaTemplate.send(topic, key, objectMapper.readValue(event.getPayload(), Object.class)).get();
                event.setStatus("SENT");
                event.setProcessedAt(Instant.now());
            } catch (Exception e) {
                int retries = event.getRetryCount() + 1;
                event.setRetryCount(retries);
                if (retries >= maxRetries) {
                    event.setStatus("FAILED");
                    log.error("Outbox event {} permanently failed after {} retries. eventType={}, aggregateId={}",
                            event.getId(), retries, event.getEventType(), event.getAggregateId(), e);
                } else {
                    log.warn("Outbox event {} failed (attempt {}). Will retry. eventType={}",
                            event.getId(), retries, event.getEventType(), e);
                }
            }
            outboxEventRepository.save(event);
        }
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case "txn.created" -> txnCreatedTopic;
            case "txn.categorized" -> txnCategorizedTopic;
            case "txn.compensation.needed" -> txnCompensationTopic;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }

    private String resolveKey(OutboxEvent event) throws JsonProcessingException {
        var node = objectMapper.readTree(event.getPayload());
        var accountIdNode = node.get("accountId");
        return accountIdNode != null ? accountIdNode.asText() : event.getAggregateId().toString();
    }
}
