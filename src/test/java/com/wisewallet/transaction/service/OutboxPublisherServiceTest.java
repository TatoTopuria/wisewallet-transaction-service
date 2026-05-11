package com.wisewallet.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisewallet.transaction.domain.model.OutboxEvent;
import com.wisewallet.transaction.domain.repository.OutboxEventRepositoryPort;
import com.wisewallet.transaction.infrastructure.messaging.OutboxPublisherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherServiceTest {

    @Mock OutboxEventRepositoryPort outboxEventRepository;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock ObjectMapper objectMapper;

    @InjectMocks OutboxPublisherService outboxPublisherService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxPublisherService, "batchSize", 50);
        ReflectionTestUtils.setField(outboxPublisherService, "maxRetries", 5);
        ReflectionTestUtils.setField(outboxPublisherService, "txnCreatedTopic", "txn.created");
        ReflectionTestUtils.setField(outboxPublisherService, "txnCategorizedTopic", "txn.categorized");
    }

    @Test
    void pollAndPublish_successfulPublish_marksAsSent() throws Exception {
        UUID aggId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(aggId)
                .eventType("txn.created")
                .payload("{\"accountId\":\"" + aggId + "\"}")
                .status("PENDING")
                .retryCount(0)
                .build();

        when(outboxEventRepository.findPendingForUpdate(50)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);
        when(objectMapper.readValue(anyString(), eq(Object.class))).thenReturn(new Object());

        var node = mock(com.fasterxml.jackson.databind.JsonNode.class);
        var textNode = mock(com.fasterxml.jackson.databind.JsonNode.class);
        when(objectMapper.readTree(anyString())).thenReturn(node);
        when(node.get("accountId")).thenReturn(textNode);
        when(textNode.asText()).thenReturn(aggId.toString());

        outboxPublisherService.pollAndPublish();

        assertThat(event.getStatus()).isEqualTo("SENT");
        verify(outboxEventRepository).save(event);
    }

    @Test
    void pollAndPublish_kafkaFailure_incrementsRetryCount() throws Exception {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("txn.created")
                .payload("{\"accountId\":\"" + UUID.randomUUID() + "\"}")
                .status("PENDING")
                .retryCount(0)
                .build();

        when(outboxEventRepository.findPendingForUpdate(50)).thenReturn(List.of(event));
        when(objectMapper.readValue(anyString(), eq(Object.class))).thenThrow(new RuntimeException("kafka error"));

        outboxPublisherService.pollAndPublish();

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void pollAndPublish_maxRetriesExceeded_marksAsFailed() throws Exception {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("txn.created")
                .payload("{\"accountId\":\"" + UUID.randomUUID() + "\"}")
                .status("PENDING")
                .retryCount(4) // one below max
                .build();

        when(outboxEventRepository.findPendingForUpdate(50)).thenReturn(List.of(event));
        when(objectMapper.readValue(anyString(), eq(Object.class))).thenThrow(new RuntimeException("kafka error"));

        outboxPublisherService.pollAndPublish();

        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo("FAILED");
    }
}
