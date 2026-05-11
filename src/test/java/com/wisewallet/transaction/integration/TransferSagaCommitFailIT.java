package com.wisewallet.transaction.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wisewallet.transaction.presentation.dto.request.TransferRequest;
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
class TransferSagaCommitFailIT {

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
    private UUID sourceAccountId;
    private UUID destAccountId;
    private UUID reservationId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sourceAccountId = UUID.randomUUID();
        destAccountId = UUID.randomUUID();
        reservationId = UUID.randomUUID();
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
    void transfer_commitFails_returns422AndNoSuccessfulCacheStored() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/reserve"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(reserveJson(reservationId, "900.00"))));

        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/credit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(accountJson(destAccountId, "1100.00"))));

        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/commit"))
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Service unavailable\"}")));

        var idempotencyKey = UUID.randomUUID().toString();
        var request = new TransferRequest(sourceAccountId, destAccountId, new BigDecimal("100.00"), "USD");
        var response = postTransfer(request, idempotencyKey);

        // Commit failure should result in a 422/503 error response
        assertThat(response.getStatusCode().value()).isIn(422, 503);

        // Idempotency key should NOT have responseBody = 201 success
        var storedKey = idempotencyKeyRepository.findAll().stream()
                .filter(k -> k.getKey().equals(idempotencyKey))
                .findFirst();
        // Either not stored as success, or stored with failure status
        storedKey.ifPresent(k -> assertThat(k.getResponseStatus()).isNotEqualTo(201));

        verify(1, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/reserve")));
        verify(1, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/credit")));
        verify(1, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/commit")));
        // release should NOT have been called — commit fail is non-recoverable
        verify(0, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/release")));
    }

    private ResponseEntity<String> postTransfer(TransferRequest request, String idempotencyKey) {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Account-Ids", sourceAccountId + "," + destAccountId);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/transactions/transfer",
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    private String reserveJson(UUID resId, String available) {
        return """
                {"reservationId":"%s","availableBalance":%s}
                """.formatted(resId, available).trim();
    }

    private String accountJson(UUID id, String balance) {
        return """
                {"id":"%s","userId":"%s","status":"ACTIVE","balance":%s,"availableBalance":%s}
                """.formatted(id, userId, balance, balance).trim();
    }
}
