package com.wisewallet.transaction.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "WiseWallet Transaction Service API",
                version = "v1",
                description = "Manages financial transactions (deposits, withdrawals, transfers) " +
                              "with idempotency guarantees, automatic categorization, and " +
                              "exactly-once Kafka event delivery via the Transactional Outbox pattern."
        ),
        servers = @Server(url = "/", description = "Default server")
)
public class OpenApiConfig {
}
