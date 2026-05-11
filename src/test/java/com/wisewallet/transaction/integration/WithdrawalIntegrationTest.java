package com.wisewallet.transaction.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.presentation.dto.request.WithdrawalRequest;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WithdrawalIntegrationTest {

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
        wireMock.resetAll();
    }

    @Test
    void withdrawal_happyPath_persistsNegativeAmountTransaction() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/debit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(accountJson(accountId, "900.00"))));

        var request = new WithdrawalRequest(accountId, new BigDecimal("100.00"), "USD", null);

        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Account-Ids", accountId.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = restTemplate.exchange("/api/transactions/withdraw",
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var txns = transactionRepository.findAll();
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(txns.get(0).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(txns.get(0).getAmount()).isEqualByComparingTo("-100.00");
    }

    @Test
    void withdrawal_insufficientFunds_returns422() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/debit"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Insufficient funds\"}")));

        var request = new WithdrawalRequest(accountId, new BigDecimal("9999.00"), "USD", null);

        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Account-Ids", accountId.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = restTemplate.exchange("/api/transactions/withdraw",
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode().value()).isIn(422, 503);
        assertThat(transactionRepository.count()).isZero();
    }

    private String accountJson(UUID id, String balance) {
        return """
                {"id":"%s","userId":"%s","status":"ACTIVE","balance":%s,"availableBalance":%s}
                """.formatted(id, userId, balance, balance).trim();
    }
}
