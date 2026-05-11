# WiseWallet — Transaction Service

A Spring Boot 3 microservice responsible for managing financial transactions within the WiseWallet platform. It handles deposits, withdrawals, and transfers between accounts, with automatic transaction categorization, idempotency protection, and reliable event publishing via the Transactional Outbox pattern.

Built with **DDD Layered Architecture** (Domain → Application → Infrastructure → Presentation) with **CQRS** and **Ports & Adapters**, enforced by ArchUnit tests.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
  - [Layer Diagram](#layer-diagram)
  - [Layer Responsibilities](#layer-responsibilities)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [Running Infrastructure Only](#running-infrastructure-only)
  - [Running the App Locally (Gradle)](#running-the-app-locally-gradle)
  - [Running the Full Stack (Docker)](#running-the-full-stack-docker)
  - [VS Code Debug Configuration](#vs-code-debug-configuration)
- [Configuration](#configuration)
  - [Profiles](#profiles)
  - [Environment Variables](#environment-variables)
- [API Reference](#api-reference)
  - [Headers](#headers)
  - [Endpoints](#endpoints)
- [Domain Model](#domain-model)
  - [Transaction Statuses](#transaction-statuses)
  - [Transaction Categories](#transaction-categories)
- [Key Design Decisions](#key-design-decisions)
  - [DDD Layered Architecture](#ddd-layered-architecture)
  - [CQRS](#cqrs)
  - [Ports & Adapters (JPA Adapter Pattern)](#ports--adapters-jpa-adapter-pattern)
  - [Domain Events](#domain-events)
  - [Idempotency](#idempotency)
  - [Transactional Outbox](#transactional-outbox)
  - [Transfer Saga](#transfer-saga)
  - [Category Rule Engine](#category-rule-engine)
  - [Auth Delegation](#auth-delegation)
  - [ArchUnit Enforcement](#archunit-enforcement)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Observability](#observability)
- [Testing](#testing)
- [Project Structure](#project-structure)

---

## Overview

The Transaction Service is one component of the WiseWallet backend. It:

- Processes **deposits**, **withdrawals**, and **transfers** with strong consistency guarantees
- Communicates with the **Account Service** via OpenFeign (with Resilience4j circuit breaker) to validate accounts and update balances
- Automatically **categorizes** transactions using MCC codes and keyword matching via a domain-layer `CategoryRuleEngine`
- Publishes domain events to **Kafka** using the **Transactional Outbox** pattern to guarantee at-least-once delivery
- Enforces **idempotency** on all write operations using client-supplied `Idempotency-Key` headers
- Exposes a rich **filtering and pagination** API for querying transaction history
- Enforces layer boundaries with **ArchUnit** tests to prevent architecture drift

---

## Architecture

### Layer Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      PRESENTATION LAYER                         │
│  TransactionController  │  CategoryController                   │
│  GlobalExceptionHandler │  Request/Response DTOs  │  MapStruct  │
└──────────────────────────────┬──────────────────────────────────┘
                               │ calls
┌──────────────────────────────▼──────────────────────────────────┐
│                      APPLICATION LAYER (CQRS)                   │
│  Commands: DepositCommandService, WithdrawalCommandService,     │
│            TransferCommandService, CategoryCommandService        │
│  Queries:  TransactionQueryService                              │
│  Shared:   IdempotencyService                                   │
│  Ports:    AccountServicePort (output port interface)           │
└───────┬───────────────────────────┬─────────────────────────────┘
        │ uses domain repo ports    │ drives output ports
┌───────▼───────────────┐  ┌───────▼─────────────────────────────┐
│     DOMAIN LAYER      │  │         INFRASTRUCTURE LAYER        │
│  Models: Transaction, │  │  Adapters: TransactionRepository-   │
│  IdempotencyKey,      │  │  Adapter, OutboxEventRepository-    │
│  OutboxEvent          │  │  Adapter, AccountServiceAdapter     │
│  Repository ports     │  │  Feign: AccountServiceFeignClient   │
│  Domain events        │  │  Messaging: DomainEventToOutbox-    │
│  Domain exceptions    │  │  Listener, OutboxPublisherService   │
│  CategoryRuleEngine   │  │  Jobs: IdempotencyCleanupJob        │
└───────────────────────┘  └─────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Package | Allowed Dependencies |
|---|---|---|
| **Domain** | `domain..` | None (pure Java) |
| **Application** | `application..` | Domain, Presentation (DTOs/Mapper) |
| **Infrastructure** | `infrastructure..` | Domain, Application |
| **Presentation** | `presentation..` | Application, Domain |

> Dependency rule is enforced by `ArchitectureTest` using ArchUnit at build time.

**Flow for a deposit:**
1. `TransactionController` validates headers → calls `DepositCommandService`
2. `DepositCommandService` checks idempotency → calls `AccountServicePort.credit()` → creates `Transaction` → `transactionRepository.save()` → `eventPublisher.publishEvent(TransactionCreatedDomainEvent)`
3. `DomainEventToOutboxListener` (BEFORE_COMMIT) serializes domain event → saves `OutboxEvent` row
4. `OutboxPublisherService` (scheduled, every 2s) polls `txn_outbox` → publishes to Kafka

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (Virtual Threads enabled via `spring.threads.virtual.enabled=true`) |
| Framework | Spring Boot 3.4.4 |
| Build | Gradle 8 (Kotlin DSL) |
| Database | PostgreSQL 16 |
| Migrations | Liquibase |
| Messaging | Apache Kafka (idempotent producer, snappy compression) |
| Service Communication | Spring Cloud OpenFeign |
| Resilience | Resilience4j (Circuit Breaker) |
| Mapping | MapStruct (compile-time, `@Mapper(componentModel = "spring")`) |
| API Docs | SpringDoc OpenAPI 3 (`@Tag`, `@Operation`, `@ApiResponse`) |
| Architecture Testing | ArchUnit 1.3.0 (layering rules enforced at build time) |
| Observability | Micrometer + Prometheus, OpenTelemetry Java Agent, Logstash JSON logging |
| Testing | JUnit 5, Mockito, Testcontainers, WireMock, Spring Kafka Test |

---

## Prerequisites

- **JDK 21+** (`JAVA_HOME` set)
- **Docker** and **Docker Compose** (for infrastructure)
- **Gradle wrapper** (`./gradlew`) — no global Gradle install needed

---

## Getting Started

### Running Infrastructure Only

Spin up PostgreSQL and Kafka without starting the application container:

```bash
docker compose up postgres kafka -d
```

Wait for both services to be healthy:

```bash
docker compose ps
```

### Running the App Locally (Gradle)

With infrastructure running, start the app using the `local` profile:

**Linux / macOS:**
```bash
INTERNAL_API_KEY=dev-key ./gradlew bootRun --args='--spring.profiles.active=local'
```

**Windows (PowerShell):**
```powershell
$env:INTERNAL_API_KEY="dev-key"; ./gradlew bootRun --args='--spring.profiles.active=local'
```

The service will be available at:
- **API:** `http://localhost:8082`
- **Actuator:** `http://localhost:8092/actuator`
- **Health:** `http://localhost:8092/actuator/health`
- **Prometheus metrics:** `http://localhost:8092/actuator/prometheus`

### Running the Full Stack (Docker)

Build and start everything including the transaction-service container:

```bash
docker compose up --build
```

> **Note:** The `transaction-service` container expects the Account Service to be reachable at `http://host.docker.internal:8081` by default. Adjust `ACCOUNT_SERVICE_URL` in `docker-compose.yml` if your setup differs.

### VS Code Debug Configuration

Create `.vscode/launch.json` at the repository root:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "TransactionServiceApplication",
      "request": "launch",
      "mainClass": "com.wisewallet.transaction.TransactionServiceApplication",
      "projectName": "wisewallet-transaction-service",
      "args": "--spring.profiles.active=local",
      "env": {
        "INTERNAL_API_KEY": "dev-key"
      }
    }
  ]
}
```

Start infrastructure first (`docker compose up postgres kafka -d`), then launch the debug configuration.

---

## Configuration

### Profiles

| Profile | Purpose | DB | Kafka | Secrets |
|---|---|---|---|---|
| `local` | Local development | `localhost:5433` | `localhost:9092` | Env vars with defaults |
| `dev` | Deployed dev environment | `${DB_URL}` | `${KAFKA_BOOTSTRAP_SERVERS}` | AWS Secrets Manager |
| `prod` | Production | `${DB_URL}` | `${KAFKA_BOOTSTRAP_SERVERS}` | AWS Secrets Manager |
| `test` | Automated tests | Testcontainers | Embedded / Testcontainers | Disabled |

Activate a profile by setting `--spring.profiles.active=<profile>` or the `SPRING_PROFILES_ACTIVE` environment variable.

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `INTERNAL_API_KEY` | **Yes** | — | API key for inter-service authentication (passed in `X-Internal-Api-Key` header) |
| `DB_URL` | dev/prod only | — | JDBC URL for PostgreSQL |
| `DB_USERNAME` | dev/prod only | — | Database username |
| `DB_PASSWORD` | local: optional | `dev_password` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | dev/prod only | — | Kafka broker addresses |
| `ACCOUNT_SERVICE_URL` | No | `http://localhost:8081` | Base URL of the Account Service |
| `MANAGEMENT_PORT` | No | `8092` | Port for Spring Boot Actuator |
| `AWS_REGION` | dev/prod only | `eu-west-1` | AWS region for Secrets Manager |

---

## API Reference

### Headers

All write endpoints require these request headers:

| Header | Type | Description |
|---|---|---|
| `X-User-Id` | UUID | ID of the authenticated user (injected by the API gateway) |
| `X-Account-Ids` | Comma-separated UUIDs | Account IDs the user owns (used for ownership validation) |
| `Idempotency-Key` | String (max 255) | Client-generated unique key to prevent duplicate operations |

### Endpoints

#### `POST /api/transactions/deposit`

Record a deposit to an account.

**Request body:**
```json
{
  "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": "250.00",
  "mccCode": "5411"
}
```

**Response:** `201 Created` — `TransactionResponse`

---

#### `POST /api/transactions/withdraw`

Record a withdrawal from an account.

**Request body:**
```json
{
  "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": "100.00",
  "mccCode": "5812"
}
```

**Response:** `201 Created` — `TransactionResponse`

---

#### `POST /api/transactions/transfer`

Transfer funds between two accounts (both must be owned by the requesting user).

**Request body:**
```json
{
  "sourceAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "destinationAccountId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "amount": "500.00"
}
```

**Response:** `201 Created` — `TransferResponse` (contains both the debit and credit transaction records)

---

#### `GET /api/transactions`

List transactions for the authenticated user with optional filtering and pagination.

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `accountId` | UUID | Filter by account |
| `from` | ISO-8601 datetime | Filter by creation date (inclusive) |
| `to` | ISO-8601 datetime | Filter by creation date (inclusive) |
| `category` | String (repeatable) | Filter by one or more categories |
| `type` | `DEPOSIT`, `WITHDRAWAL`, `TRANSFER` | Filter by transaction type |
| `minAmount` | Decimal | Minimum absolute amount |
| `maxAmount` | Decimal | Maximum absolute amount |
| `status` | `PENDING`, `DEBITED`, `COMPLETED`, `FAILED` | Filter by status |
| `page` | int (default: 0) | Page number |
| `size` | int (default: 10, max: 100) | Page size |
| `sort` | `field,direction` (default: `createdAt,desc`) | Sort field and direction |

**Response:** `200 OK` — paginated `Page<TransactionResponse>`

---

#### `GET /api/transactions/{id}/summary`

Get a monthly aggregated summary for a user.

**Response:** `200 OK` — `MonthlySummaryResponse`

---

#### `PUT /api/transactions/{id}/category`

Manually override the category of a transaction.

**Request body:**
```json
{
  "category": "GROCERIES"
}
```

**Response:** `200 OK` — updated `TransactionResponse`

---

## Domain Model

### Transaction Statuses

```
PENDING → DEBITED → COMPLETED
              ↓
           FAILED
```

| Status | Description |
|---|---|
| `PENDING` | Transfer reserved on source account; awaiting commit |
| `DEBITED` | Funds debited from source; credit to destination in progress |
| `COMPLETED` | Transaction fully settled |
| `FAILED` | Operation failed; any reserved funds have been released |

### Transaction Categories

Categories are assigned automatically from MCC codes and description keywords. They can also be overridden manually via `PUT /api/transactions/{id}/category`.

| Category | Assignment Source |
|---|---|
| `GROCERIES` | MCC 5411, 5412, 5422 / keywords: grocery, supermarket, … |
| `DINING` | MCC 5812, 5813 / keywords: restaurant, cafe, pizza, … |
| `TRANSPORT` | MCC 4111, 4121, 5541 / keywords: uber, taxi, gas, … |
| `SHOPPING` | MCC 5940+ / keywords: amazon, shop, store, … |
| `UTILITIES` | MCC 4900 / keywords: electric, water, internet, … |
| `ENTERTAINMENT` | MCC 7832, 7993 / keywords: netflix, cinema, spotify, … |
| `HEALTHCARE` | MCC 5912, 8011, 8049 / keywords: pharmacy, clinic, hospital, … |
| `EDUCATION` | MCC 8220, 8299 / keywords: university, tuition, course, … |
| `TRAVEL` | MCC 4511, 7011, 7012 / keywords: airline, hotel, flight, … |
| `HOUSING` | MCC 6552 / keywords: rent, mortgage, landlord |
| `PERSONAL_CARE` | keywords: salon, haircut, barber, spa, gym |
| `HEALTH_FITNESS` | MCC 7941, 7997 / keywords: fitness, yoga, workout, … |
| `INSURANCE` | MCC 6300 / keywords: insurance, premium, coverage |
| `SUBSCRIPTIONS` | keywords: subscription, membership, renewal, plan |
| `INCOME` | keywords: salary, payroll, dividend, bonus |
| `TRANSFER` | Assigned automatically to all `TRANSFER`-type transactions |
| `OTHER` | Fallback when no MCC or keyword matches |

---

## Key Design Decisions

### DDD Layered Architecture

The service is structured into four strict layers following Domain-Driven Design principles:

- **Domain** — Pure domain model with zero framework dependencies. Contains JPA entities, enums, value objects, repository port interfaces, domain events (Java records), domain exceptions, and domain services (`CategoryRuleEngine`). This layer owns the business language.
- **Application** — Use case orchestrators. Command services handle writes (deposit, withdrawal, transfer, category override). Query services handle reads (`TransactionQueryService`). The `IdempotencyService` is a shared application service. All inter-layer calls go through domain port interfaces. **No HTTP, no Kafka, no JPA here.**
- **Infrastructure** — Framework implementations of domain ports: JPA repository adapters, Feign client adapter, outbox listener, outbox publisher, scheduled cleanup jobs, Kafka config.
- **Presentation** — REST controllers, request/response DTOs, MapStruct mapper, global exception handler.

### CQRS

Write and read paths are explicitly separated:

- **Command services** (write): `DepositCommandService`, `WithdrawalCommandService`, `TransferCommandService`, `CategoryCommandService` — each annotated `@Transactional`, own `@ApplicationEvent` publishing, store idempotency responses.
- **Query services** (read): `TransactionQueryService` — `@Transactional(readOnly = true)`, no side effects.

This separation makes each use case independently testable and keeps write-path complexity isolated.

### Ports & Adapters (JPA Adapter Pattern)

Domain repository ports are plain Java interfaces in `domain/repository/`. Infrastructure provides implementations in `infrastructure/persistence/` via a **dedicated adapter class** that wraps the Spring Data JPA repository:

```
TransactionRepositoryPort          (domain — interface)
    ↑ implements
TransactionRepositoryAdapter       (infrastructure — @Repository class)
    → delegates to
TransactionJpaRepository           (infrastructure — extends JpaRepository<Transaction, UUID>)
```

This pattern avoids generic type conflicts (Spring Data's `save(S)` vs domain's `save(Transaction)`) and keeps domain ports free of JPA annotations. The same pattern is applied to `IdempotencyKey` and `OutboxEvent`.

Similarly, the external Account Service integration uses an output port:

```
AccountServicePort                 (application/port/out — interface)
    ↑ implements
AccountServiceAdapter              (infrastructure/client — @Component)
    → delegates to
AccountServiceFeignClient          (infrastructure/client — @FeignClient)
```

### Domain Events

Application services publish **domain events** via Spring's `ApplicationEventPublisher` within the transaction:

```java
eventPublisher.publishEvent(new TransactionCreatedDomainEvent(txn));
```

The `DomainEventToOutboxListener` in infrastructure listens with `@TransactionalEventListener(phase = BEFORE_COMMIT)`, which guarantees the outbox row is written **atomically** with the business transaction. If the business transaction rolls back, the outbox row is never written.

Domain events are plain Java records in `domain/event/` — no infrastructure imports.

### Idempotency

Every write operation requires a client-supplied `Idempotency-Key` header (max 255 chars). The `IdempotencyService`:

1. Attempts to insert the key into `idempotency_keys` in a **`REQUIRES_NEW` transaction** (isolated from the parent).
2. On `DataIntegrityViolationException` (duplicate key), reads the existing row:
   - If `responseBody` is set → returns a `Cached` result; the controller re-serializes the original response without reprocessing.
   - If `responseBody` is null (request still in-flight) → throws `DuplicateIdempotencyKeyException` (→ 409).
3. On success, the command service serializes the final response JSON and calls `idempotencyService.complete()` to store it.

Uses Java 21 **sealed interfaces** for the result type (`IdempotencyResult.Proceed | IdempotencyResult.Cached`).

Expired keys are cleaned up hourly by `IdempotencyCleanupJob` (configurable TTL, default 48 hours).

### Transactional Outbox

Domain events are not published to Kafka directly inside the business transaction. Instead:

1. `DomainEventToOutboxListener` writes a row to `txn_outbox` **before commit** (same transaction).
2. `OutboxPublisherService` polls the table every 2 seconds (`SELECT … FOR UPDATE SKIP LOCKED`) and publishes pending events to Kafka.
3. On Kafka failure: retry counter increments. After `max-retries` (default 5), the event is marked `FAILED` and logged as an error.
4. Successfully published events are marked `SENT`.

This guarantees **at-least-once delivery** even if Kafka is temporarily unavailable and decouples Kafka from the DB commit.

### Transfer Saga

Transfers coordinate with the Account Service through a three-phase saga:

```
Phase 1 [TX1 — DB] Setup:   Create PENDING debit + credit Transaction rows
Phase 2 [Feign]    Reserve:  accountServicePort.reserve(source, amount, debitTxnId)
Phase 3 [Feign]    Debit:    accountServicePort.debit(source, amount)
Phase 4 [TX3 — DB] Mark:     debitTxn → DEBITED
Phase 5 [Feign]    Credit:   accountServicePort.credit(destination, amount)
Phase 6 [TX4 — DB] Complete: both legs → COMPLETED, publish OutboxEvents
```

**Compensation:** On any failure after Phase 2, `accountServicePort.release(source, reservationId)` is called to undo the reservation, and both legs are set to `FAILED`.

### Category Rule Engine

`CategoryRuleEngine` (domain service) applies a priority chain:

1. **Transfer type** → always assigns `TRANSFER`
2. **MCC code lookup** → `MccCategoryMapping.categorize(mccCode)` returns `Optional<TransactionCategory>`
3. **Keyword matching** → `KeywordCategoryMapping.categorize(description)` returns `Optional<TransactionCategory>`
4. **Fallback** → `OTHER`

Both mapping classes are plain Java (no Spring), constructable in unit tests without a context. `CategoryRuleEngine` is a `@Component` and depends on both mappers via constructor injection.

### Auth Delegation

This service has **no Spring Security**. Authentication and JWT validation are handled by the API Gateway, which forwards verified claims as HTTP headers:

| Header | Content |
|---|---|
| `X-User-Id` | UUID of the authenticated user |
| `X-Account-Ids` | Comma-separated UUIDs of accounts the user owns |

The controller validates account ownership by checking that the requested `accountId` (from request body or query param) is present in `X-Account-Ids`. Missing or violated headers return 400/403 via `GlobalExceptionHandler`.

### ArchUnit Enforcement

`ArchitectureTest` (JUnit 5 + ArchUnit) verifies the layering rule at build time:

```java
layeredArchitecture()
    .consideringOnlyDependenciesInLayers()
    .layer("Domain").definedBy("com.wisewallet.transaction.domain..")
    .layer("Application").definedBy("com.wisewallet.transaction.application..")
    .layer("Infrastructure").definedBy("com.wisewallet.transaction.infrastructure..")
    .layer("Presentation").definedBy("com.wisewallet.transaction.presentation..")
    .whereLayer("Domain").mayNotAccessAnyLayer()
    .whereLayer("Application").mayOnlyAccessLayers("Domain", "Presentation")
    .whereLayer("Infrastructure").mayOnlyAccessLayers("Domain", "Application")
    .whereLayer("Presentation").mayOnlyAccessLayers("Application", "Domain")
```

This prevents accidental imports that break the dependency rule (e.g., domain importing infrastructure).

---

## Database

The service owns a dedicated `transaction` schema in PostgreSQL. Schema migrations are managed by **Liquibase** and run automatically on startup.

| Table | Description |
|---|---|
| `transaction.transactions` | Core transaction records |
| `transaction.idempotency_keys` | Idempotency key store with TTL |
| `transaction.txn_outbox` | Outbox events pending Kafka publication |

Liquibase changelogs are in `src/main/resources/db/changelog/`.

---

## Kafka Topics

| Topic | Event | Published When |
|---|---|---|
| `txn.created` | `TransactionCreatedEvent` | A deposit, withdrawal, or transfer is successfully recorded |
| `txn.categorized` | `TransactionCategorizedEvent` | A transaction's category is assigned or updated |

Both topics are auto-created by Kafka on first publish. For production, pre-create topics with appropriate partition counts and replication factors.

---

## Observability

| Capability | Detail |
|---|---|
| **Health** | `GET http://localhost:8092/actuator/health` — includes DB and Kafka connectivity |
| **Metrics** | Prometheus scrape endpoint at `GET http://localhost:8092/actuator/prometheus` |
| **Tracing** | OpenTelemetry Java Agent attached in the Docker image (configure via `OTEL_*` environment variables) |
| **Logging** | Structured JSON (Logstash format) in `dev`/`prod` profiles; human-readable in `local`/`test`. MDC fields: `correlationId`, `traceId`, `spanId`, `userId` |
| **Correlation ID** | `CorrelationIdFilter` propagates or generates a `correlationId` on every request |

---

## Testing

The project has comprehensive test coverage across multiple layers:

```bash
# Run all tests with coverage report
./gradlew test

# Run only unit tests (fast, no Docker)
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
gradle test --tests "com.wisewallet.transaction.domain.*" \
            --tests "com.wisewallet.transaction.application.*" \
            --tests "com.wisewallet.transaction.presentation.*" \
            --tests "com.wisewallet.transaction.arch.*"

# Open HTML coverage report
open build/reports/jacoco/test/html/index.html
```

| Test suite | Package | Description | Infrastructure |
|---|---|---|---|
| Domain unit tests | `domain.*` | `CategoryRuleEngine`, `MccCategoryMapping`, `KeywordCategoryMapping` | None |
| Application unit tests | `service.*` | Command and query services with mocked ports | None (Mockito) |
| Architecture tests | `arch.*` | ArchUnit layer boundary enforcement | None |
| Controller slice tests | `controller.*` | MockMvc with `@WebMvcTest`, exercises validation + exception handling | None |
| Repository slice tests (TODO) | `repository.*` | Spring Data JPA queries, Specification filters | Testcontainers (PostgreSQL) |
| Integration tests (TODO) | `integration.*` | Full happy path + saga rollback scenarios | Testcontainers (PostgreSQL + Kafka) + WireMock |

**Coverage targets (JaCoCo):** 85% instruction coverage, 75% branch coverage.

---

## Project Structure

```
wisewallet-transaction-service/
├── docker/
│   └── Dockerfile                          # Multi-stage build with OTel agent
├── docker-compose.yml                      # Local dev infrastructure
├── gradle/
│   └── libs.versions.toml                  # Centralized dependency versions
├── src/
│   ├── main/
│   │   ├── java/com/wisewallet/transaction/
│   │   │   ├── TransactionServiceApplication.java
│   │   │   │
│   │   │   ├── domain/                     # ── DOMAIN LAYER ──────────────────
│   │   │   │   ├── model/                  # JPA entities + enums + value objects
│   │   │   │   │   ├── Transaction.java
│   │   │   │   │   ├── IdempotencyKey.java
│   │   │   │   │   ├── OutboxEvent.java
│   │   │   │   │   ├── TransactionCategory.java   # 18 values
│   │   │   │   │   ├── TransactionStatus.java
│   │   │   │   │   ├── TransactionType.java
│   │   │   │   │   └── vo/Money.java              # @Embeddable value object
│   │   │   │   ├── repository/             # Port interfaces (no JPA annotations)
│   │   │   │   │   ├── TransactionRepositoryPort.java
│   │   │   │   │   ├── IdempotencyKeyRepositoryPort.java
│   │   │   │   │   └── OutboxEventRepositoryPort.java
│   │   │   │   ├── event/                  # Domain events (Java records)
│   │   │   │   │   ├── TransactionCreatedDomainEvent.java
│   │   │   │   │   └── TransactionCategorizedDomainEvent.java
│   │   │   │   ├── exception/              # Domain exceptions
│   │   │   │   │   ├── BusinessRuleException.java
│   │   │   │   │   ├── TransactionNotFoundException.java
│   │   │   │   │   ├── DuplicateIdempotencyKeyException.java
│   │   │   │   │   ├── SameAccountTransferException.java
│   │   │   │   │   ├── InsufficientFundsException.java
│   │   │   │   │   ├── AccountNotFoundException.java
│   │   │   │   │   ├── AccountInactiveException.java
│   │   │   │   │   └── AccountServiceUnavailableException.java
│   │   │   │   └── service/                # Domain services (pure Java)
│   │   │   │       ├── CategoryRuleEngine.java
│   │   │   │       ├── MccCategoryMapping.java
│   │   │   │       └── KeywordCategoryMapping.java
│   │   │   │
│   │   │   ├── application/                # ── APPLICATION LAYER (CQRS) ──────
│   │   │   │   ├── command/                # Write use cases
│   │   │   │   │   ├── DepositCommandService.java
│   │   │   │   │   ├── WithdrawalCommandService.java
│   │   │   │   │   ├── TransferCommandService.java
│   │   │   │   │   └── CategoryCommandService.java
│   │   │   │   ├── query/                  # Read use cases
│   │   │   │   │   └── TransactionQueryService.java
│   │   │   │   ├── port/out/               # Output port interfaces
│   │   │   │   │   ├── AccountServicePort.java
│   │   │   │   │   └── ReservationResult.java
│   │   │   │   └── shared/                 # Application-layer shared services
│   │   │   │       └── IdempotencyService.java
│   │   │   │
│   │   │   ├── infrastructure/             # ── INFRASTRUCTURE LAYER ──────────
│   │   │   │   ├── persistence/            # JPA + repository adapters
│   │   │   │   │   ├── TransactionJpaRepository.java       # Spring Data
│   │   │   │   │   ├── TransactionRepositoryAdapter.java   # implements port
│   │   │   │   │   ├── IdempotencyKeyJpaRepository.java
│   │   │   │   │   ├── IdempotencyKeyRepositoryAdapter.java
│   │   │   │   │   ├── OutboxEventJpaRepository.java
│   │   │   │   │   ├── OutboxEventRepositoryAdapter.java
│   │   │   │   │   └── spec/               # JPA Specifications (dynamic filters)
│   │   │   │   ├── client/                 # OpenFeign client + adapter
│   │   │   │   │   ├── AccountServiceFeignClient.java
│   │   │   │   │   ├── AccountServiceAdapter.java          # implements port
│   │   │   │   │   ├── config/             # Feign + Resilience4j config
│   │   │   │   │   └── dto/                # Feign request/response DTOs
│   │   │   │   ├── messaging/              # Kafka outbox
│   │   │   │   │   ├── DomainEventToOutboxListener.java    # @TransactionalEventListener
│   │   │   │   │   ├── OutboxPublisherService.java         # @Scheduled poller
│   │   │   │   │   └── event/              # Kafka event payload records
│   │   │   │   ├── config/                 # Spring config
│   │   │   │   │   ├── KafkaConfig.java
│   │   │   │   │   └── filter/CorrelationIdFilter.java
│   │   │   │   └── job/                    # Scheduled jobs
│   │   │   │       └── IdempotencyCleanupJob.java
│   │   │   │
│   │   │   └── presentation/               # ── PRESENTATION LAYER ────────────
│   │   │       ├── controller/             # REST controllers
│   │   │       │   ├── TransactionController.java
│   │   │       │   └── CategoryController.java
│   │   │       ├── dto/
│   │   │       │   ├── request/            # DepositRequest, WithdrawalRequest, etc.
│   │   │       │   └── response/           # TransactionResponse, TransferResponse, etc.
│   │   │       ├── mapper/
│   │   │       │   └── TransactionMapper.java     # MapStruct
│   │   │       └── exception/
│   │   │           └── GlobalExceptionHandler.java
│   │   └── resources/
│   │       ├── application.yml             # Base configuration
│   │       ├── application-local.yml       # Local dev overrides
│   │       ├── application-dev.yml         # Dev environment overrides
│   │       ├── application-prod.yml        # Production overrides
│   │       ├── logback-spring.xml          # Profile-aware logging config
│   │       └── db/changelog/               # Liquibase migration scripts
│   └── test/
│       ├── java/com/wisewallet/transaction/
│       │   ├── arch/                       # ArchUnit layering tests
│       │   ├── categorization/             # Domain: CategoryRuleEngine unit tests
│       │   ├── controller/                 # Presentation: MockMvc @WebMvcTest
│       │   ├── domain/                     # Domain model unit tests
│       │   ├── service/                    # Application: command/query service unit tests
│       │   ├── repository/                 # Infrastructure: Testcontainers JPA tests
│       │   └── integration/                # Full stack: Testcontainers + WireMock
│       └── resources/
│           └── application-test.yml        # Test profile configuration
└── build.gradle.kts                        # Gradle build script
```
