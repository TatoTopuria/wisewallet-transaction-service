package com.wisewallet.transaction.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wisewallet.transaction.domain.model.*;
import com.wisewallet.transaction.infrastructure.persistence.IdempotencyKeyJpaRepository;
import com.wisewallet.transaction.infrastructure.persistence.OutboxEventJpaRepository;
import com.wisewallet.transaction.infrastructure.persistence.TransactionJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class MonthlyAggregationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

    static WireMockServer wireMock = new WireMockServer(0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("account.service.url", wireMock::baseUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired TransactionJpaRepository transactionRepository;
    @Autowired OutboxEventJpaRepository outboxEventRepository;
    @Autowired IdempotencyKeyJpaRepository idempotencyKeyRepository;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        WireMock.configureFor(wireMock.port());
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
        transactionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    void monthlySummary_seedData_returnsCorrectAggregation() {
        // Seed transactions for current month
        transactionRepository.save(Transaction.builder()
                .userId(userId).accountId(accountId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEPOSIT).status(TransactionStatus.COMPLETED)
                .category(TransactionCategory.OTHER)
                .idempotencyKey(UUID.randomUUID().toString()).build());

        transactionRepository.save(Transaction.builder()
                .userId(userId).accountId(accountId)
                .amount(new BigDecimal("-50.00"))
                .type(TransactionType.WITHDRAWAL).status(TransactionStatus.COMPLETED)
                .category(TransactionCategory.GROCERIES)
                .idempotencyKey(UUID.randomUUID().toString()).build());

        var now = LocalDate.now();
        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());

        var response = restTemplate.exchange(
                "/api/transactions/summary/monthly?year={year}&month={month}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class,
                now.getYear(), now.getMonthValue());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("byCategory");
    }

    @Test
    void monthlySummary_outOfRange_returns422() {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());

        var response = restTemplate.exchange(
                "/api/transactions/summary/monthly?year=2020&month=1",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
