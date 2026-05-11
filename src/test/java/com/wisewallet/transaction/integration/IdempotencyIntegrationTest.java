package com.wisewallet.transaction.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wisewallet.transaction.presentation.dto.request.DepositRequest;
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
class IdempotencyIntegrationTest {

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
    void twoIdenticalRequests_sameIdempotencyKey_onlyOneRecordCreated() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/credit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(accountJson(accountId, "1000.00"))));

        var idempotencyKey = UUID.randomUUID().toString();
        var request = new DepositRequest(accountId, new BigDecimal("100.00"), "USD", null);

        var first = postDeposit(request, idempotencyKey);
        var second = postDeposit(request, idempotencyKey);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same body returned both times
        assertThat(first.getBody()).isEqualTo(second.getBody());

        // Only one transaction and one idempotency key in DB
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);

        // Account service called exactly once
        verify(1, postRequestedFor(urlPathMatching("/internal/accounts/.*/balances/.*/credit")));
    }

    @Test
    void twoRequestsDifferentIdempotencyKeys_createsTwoRecords() {
        stubFor(post(urlPathMatching("/internal/accounts/.*/balances/.*/credit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(accountJson(accountId, "1000.00"))));

        var request = new DepositRequest(accountId, new BigDecimal("100.00"), "USD", null);

        postDeposit(request, UUID.randomUUID().toString());
        postDeposit(request, UUID.randomUUID().toString());

        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(2);
    }

    private ResponseEntity<String> postDeposit(DepositRequest request, String idempotencyKey) {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Account-Ids", accountId.toString());
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/transactions/deposit",
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    private String accountJson(UUID id, String balance) {
        return """
                {"id":"%s","userId":"%s","status":"ACTIVE","balance":%s,"availableBalance":%s}
                """.formatted(id, userId, balance, balance).trim();
    }
}
