package com.wisewallet.transaction.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wisewallet.transaction.domain.model.TransactionStatus;
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
class TransferSagaRollbackIT {

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
    void transfer_reservationFails_bothLegsMarkedFailed() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/reserve"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Insufficient balance\"}")));

        var request = new TransferRequest(sourceAccountId, destAccountId, new BigDecimal("100.00"), "USD");
        var response = postTransfer(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        var txns = transactionRepository.findAll();
        assertThat(txns).hasSize(2);
        assertThat(txns).allMatch(t -> t.getStatus() == TransactionStatus.FAILED);

        // release should NOT have been called (reservation never happened)
        verify(0, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/release")));
    }

    @Test
    void transfer_creditFails_releaseCalledAndBothLegsMarkedFailed() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/reserve"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(reserveJson(reservationId, "900.00"))));

        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/credit"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Account inactive\"}")));

        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/release"))
                .willReturn(aResponse().withStatus(200)));

        var request = new TransferRequest(sourceAccountId, destAccountId, new BigDecimal("100.00"), "USD");
        var response = postTransfer(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        var txns = transactionRepository.findAll();
        assertThat(txns).hasSize(2);
        assertThat(txns).allMatch(t -> t.getStatus() == TransactionStatus.FAILED);

        verify(1, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/release")));
    }

    private ResponseEntity<String> postTransfer(TransferRequest request) {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Account-Ids", sourceAccountId + "," + destAccountId);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/transactions/transfer",
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    private String reserveJson(UUID resId, String available) {
        return """
                {"reservationId":"%s","availableBalance":%s}
                """.formatted(resId, available).trim();
    }
}
