# WiseWallet — Project Skeleton & Cross-Cutting Foundation Plan

> **Session note:** This document is the canonical reference for all cross-cutting
> decisions made in the foundation planning session. Do not alter confirmed decisions
> without noting the change and the reason. Service-level plans build on top of this.

---

## Confirmed Decisions

### From user answers

| # | Topic | Decision |
|---|---|---|
| 1 | Repo structure | **Polyrepo** — 6 separate Git repos (gateway, 4 services, infra) |
| 2 | Shared lib | **Zero runtime sharing** — duplicate cross-cutting code per service |
| 3 | Root Java package | `com.wisewallet.*` |
| 4 | Module names | `account-service`, `transaction-service`, `notification-service`, `advisor-service`, `gateway` |
| 5 | Lombok | **Yes** |
| 6 | MapStruct | **Yes** |
| 7 | Virtual threads | **Selective** — `transaction-service`, `notification-service`, `advisor-service` only |
| 8 | Java 21 records | **Yes** — all DTOs, request/response objects, event payloads |
| 9 | Sealed types | **Where natural** — not forced |
| 10 | DB migration tool | **Liquibase** |
| 11 | Error model | **Spring Boot default** error structure + field-level `errors` array on validation failures |
| 12 | Pagination | **Spring `Page<T>` envelope** shape |
| 13 | PostgreSQL isolation | **One container per service** in Docker Compose |
| 14 | Kafka mode | **Confluent KRaft** (no Zookeeper) — Kafka UI included |
| 15 | Health checks | **Yes** on all core containers |
| 16 | LocalStack version | **Pinned to `3.8`** |
| 17 | Local tracing | **Jaeger** included in Docker Compose |
| 18 | Local logging | **ELK stack** (Elasticsearch + Logstash + Kibana) in Docker Compose |
| 19 | OTel strategy | **OTel Java agent** (JVM attach, zero-code) |
| 20 | Kafka trace propagation | **Yes** — W3C `traceparent`/`tracestate` headers on Kafka records |
| 21 | Branch strategy | **Gitflow** — `main` + `develop` |
| 22 | Commit format | **Conventional Commits** (enforced via commitlint in CI) |
| 23 | JWT access token TTL | **15 minutes** |
| 24 | JWT refresh token TTL | **7 days** |
| 25 | JWT account ID embedding | **Yes** — `accountIds` embedded in JWT payload |

### Architect's calls (flagged, not user-specified)

| Topic | Decision | Rationale |
|---|---|---|
| Gradle DSL | **Kotlin (`.kts`)** | Modern standard, better IDE support |
| BOM/Platform module | **Version catalog only** (`libs.versions.toml`), no separate BOM module | Appropriate for polyrepo; BOM module adds complexity without value |
| DB isolation (prod) | **Separate schemas** inside a single RDS instance | Balances isolation vs. cost |
| Spring Config Server | **None** — per-service `application.yml` with profile overrides | Simpler; sufficient for 5 services |
| Local secrets | **`.env` file per repo**, never committed; `.env.example` committed | Standard, safe, no extra tooling |
| Prod secrets | **AWS Secrets Manager** via Spring Cloud AWS | Integrates cleanly with ECS |
| JWT signing algorithm | **HMAC-SHA256 (`HS256`)** symmetric key | Sufficient for internal-only token issuance |
| Correlation ID header | **`X-Correlation-ID`** | Clear, non-ambiguous, human-debuggable |
| Logback format | **Human-readable in `local`; JSON in `dev` + `prod`** | Developer ergonomics vs. machine-parseable logs |
| PR template | **Included in this plan**; GH Actions YAML deferred to CI/CD step | Keeps scope manageable |

---

## Open / Deferred Questions

These must be resolved before planning the relevant service.

| # | Question | Needed for |
|---|---|---|
| S1 | Account Service locking strategy: spec says both pessimistic locking (bullets) and optimistic (`@Version` in data model) — which? | Account Service plan |
| S2 | Internal Account↔Transaction call: spec says both OpenFeign and gRPC/REST — which? | Account + Transaction Service plans |
| S3 | AI Advisor stack: Spring AI vs. LangChain4j (both listed in spec) — which is primary? | Advisor Service plan |
| S4 | Account Service Kafka consumer: what event(s) does it consume and what "balance sync" does it perform? | Account Service plan |
| S5 | Missing withdraw endpoint: is withdrawal a separate `POST /api/transactions/withdraw`, or is it folded into deposit with a negative amount, or something else? | Transaction Service plan |
| S6 | Service discovery: spec checklist mentions it but no Eureka/Consul is defined — in scope? | Foundation + all services |

---

## 1. Repository Layout

### 1.1 Git Organization

Six separate repos under a single GitHub Organization (e.g., `WiseWallet-io`):

| Repo name | Purpose | Default branch |
|---|---|---|
| `wisewallet-gateway` | API Gateway (Spring Cloud Gateway) | `develop` |
| `wisewallet-account-service` | User auth + account management | `develop` |
| `wisewallet-transaction-service` | Transaction processing, ledger | `develop` |
| `wisewallet-notification-service` | Kafka consumer, SNS/SQS fan-out | `develop` |
| `wisewallet-advisor-service` | AI/RAG advisor | `develop` |
| `wisewallet-infra` | Terraform, Docker Compose, observability configs | `develop` |

### 1.2 Per-Repo Internal Structure

Every service repo uses this canonical layout (`account-service` as example):

```
wisewallet-account-service/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties        ← Gradle 8.13
│   └── libs.versions.toml                  ← canonical version catalog (identical across repos)
├── src/
│   ├── main/
│   │   ├── java/com/wisewallet/account/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── db/changelog/
│   │           └── db.changelog-master.xml
│   └── test/
│       ├── java/com/wisewallet/account/
│       └── resources/
│           └── application-test.yml
├── docker/
│   └── Dockerfile
├── .env.example
├── .gitignore
├── .github/
│   ├── workflows/                           ← populated in CI/CD planning step
│   └── pull_request_template.md
└── README.md
```

`wisewallet-infra` repo layout:

```
wisewallet-infra/
├── terraform/
│   ├── modules/
│   │   ├── ecs-service/
│   │   ├── rds/
│   │   ├── msk/
│   │   ├── elasticache/
│   │   └── networking/
│   └── envs/
│       ├── dev/
│       └── prod/
├── docker-compose/
│   ├── docker-compose.yml
│   ├── docker-compose.override.yml          ← local dev application containers
│   ├── prometheus.yml
│   ├── logstash/
│   │   └── pipeline.conf
│   ├── grafana/
│   │   └── dashboards/
│   └── .env.example
└── README.md
```

---

## 2. Gradle Build Configuration

### 2.1 Gradle Wrapper

Pin to **Gradle 8.13** (`gradle-wrapper.properties`) in every repo. Update all repos in lockstep.

### 2.2 `settings.gradle.kts`

Minimal — enables the version catalog and sets the project name:

```kotlin
rootProject.name = "account-service"
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
```

### 2.3 `build.gradle.kts` Structure

Each repo is a **single-module Gradle project** (no subprojects).

**Plugins:**
- `org.springframework.boot` (version from catalog alias `springBoot`)
- `io.spring.dependency-management` (alias `springDepMgmt`)
- `java`

No Lombok Gradle plugin — use `annotationProcessor` configuration instead.

**Java toolchain:**
```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

**Annotation processor ordering** (critical — wrong order = broken MapStruct):

```
1. compileOnly + annotationProcessor: lombok
2. annotationProcessor: lombok-mapstruct-binding:0.2.0
3. implementation + annotationProcessor: mapstruct
```

Without `lombok-mapstruct-binding`, MapStruct doesn't see Lombok-generated getters/setters.

**Test task:**
```kotlin
tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")  // required for Testcontainers on Java 21
}
```

### 2.4 `gradle/libs.versions.toml` — Canonical Version Table

This file is **identical across all repos**. Version bumps are applied to all repos simultaneously.
Commit message for bumps: `chore(deps): bump spring-boot to X.Y.Z`.

#### `[versions]`

| Alias | Library | Version |
|---|---|---|
| `spring-boot` | Spring Boot | `3.4.4` |
| `spring-cloud` | Spring Cloud BOM | `2024.0.1` |
| `spring-ai` | Spring AI | `1.0.0` |
| `spring-cloud-aws` | Spring Cloud AWS | `3.3.0` |
| `lombok` | Lombok | `1.18.36` |
| `lombok-mapstruct-binding` | Binding helper | `0.2.0` |
| `mapstruct` | MapStruct | `1.6.3` |
| `liquibase` | Liquibase | pulled from Boot BOM |
| `postgresql` | PostgreSQL driver | pulled from Boot BOM |
| `testcontainers` | Testcontainers BOM | `1.20.4` |
| `resilience4j` | Resilience4j Spring Boot 3 | `2.3.0` |
| `logstash-logback-encoder` | Logstash Logback encoder | `8.0` |
| `otel-agent` | OTel Java agent (Docker only) | `2.12.0` |

#### `[plugins]`

| Alias | Plugin ID | Version |
|---|---|---|
| `springBoot` | `org.springframework.boot` | ref: `spring-boot` |
| `springDepMgmt` | `io.spring.dependency-management` | `1.1.7` |

#### `[libraries]` — BOM imports (used via Gradle `platform()`)

| Alias | Group:Artifact |
|---|---|
| `spring-boot-bom` | `org.springframework.boot:spring-boot-dependencies` |
| `spring-cloud-bom` | `org.springframework.cloud:spring-cloud-dependencies` |
| `spring-ai-bom` | `org.springframework.ai:spring-ai-bom` |
| `testcontainers-bom` | `org.testcontainers:testcontainers-bom` |

---

## 3. Java 21 Feature Usage Plan

### 3.1 Records — Mandatory Use

Use records for:
- All HTTP **request DTOs** — `CreateAccountRequest`, `TransactionRequest`, `ChatRequest`, etc.
- All HTTP **response DTOs** — `AccountResponse`, `TransactionResponse`, `PagedResponse<T>`, etc.
- All **Kafka event payload classes** — `TransactionCreatedEvent`, `BalanceLowEvent`, etc.
- **Internal value objects** — `Money(BigDecimal amount, String currency)`, `UserId(UUID value)`, `AccountId(UUID value)`

Do **not** use records for:
- JPA `@Entity` classes — use Lombok `@Data` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor`
- Objects needing complex builders with many optional fields — use Lombok `@Builder` on a regular class

Use **compact constructors** for inline validation:
```java
record Money(BigDecimal amount, String currency) {
    Money {
        if (amount.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException(...);
        Objects.requireNonNull(currency);
    }
}
```

### 3.2 Sealed Types — Confirmed Candidates

| Sealed type | Permitted implementations | Location | Benefit |
|---|---|---|---|
| `sealed interface DomainEvent` | `TransactionCreatedEvent`, `BalanceLowEvent`, `AccountCreatedEvent` | Each service defines its own (NOT shared) | Exhaustive switch in Kafka consumers |
| `sealed interface AdvisorResult` | `SuccessResult(String content)`, `ErrorResult(String reason)` | `advisor-service` | Clean RAG response handling |
| `sealed interface TransactionCommand` | `DepositCommand`, `WithdrawalCommand`, `TransferCommand` | `transaction-service` | Eliminates instanceof chains |

Use enums (not sealed types) for: `AccountType`, `NotificationChannel`, `TransactionCategory`.

### 3.3 Virtual Threads

Enabled via `application.yml` **only in the three designated services**:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

| Service | Virtual Threads | Rationale |
|---|---|---|
| `gateway-service` | **No** | Reactive (Netty/WebFlux) — virtual threads don't apply |
| `account-service` | **No** | Low concurrency; standard Tomcat threads sufficient |
| `transaction-service` | **Yes** | High-throughput blocking I/O: DB writes, Feign calls, Kafka publishes |
| `notification-service` | **Yes** | I/O-bound: Kafka polling, SNS/SQS calls, email delivery |
| `advisor-service` | **Yes** | Long-blocking I/O: LLM API calls, vector DB queries |

> **Important:** When virtual threads are enabled, set
> `spring.datasource.hikari.maximum-pool-size` to `10–20` (down from the default 10 —
> already low, but confirm). Virtual threads park rather than block OS threads;
> a huge connection pool is wasteful.

### 3.4 Switch Expressions

Use for:
- Transaction categorization rule engine
- Kafka consumer event routing in `notification-service` and `advisor-service`
- `DomainEvent` sealed-type dispatch

### 3.5 Text Blocks

Use for:
- Multi-line SQL in Liquibase formatted SQL changesets
- Spring AI system prompt definitions in `advisor-service`

---

## 4. Error Model & API Conventions

### 4.1 HTTP Error Response Structure

Spring Boot default structure, extended with a field-level `errors` array for validation failures.

**Standard error** (4xx / 5xx without validation):
```json
{
  "timestamp": "2026-04-08T10:00:00.000+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Account not found",
  "path": "/api/accounts/123"
}
```

**Validation error** (400 from bean validation):
```json
{
  "timestamp": "2026-04-08T10:00:00.000+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/accounts",
  "errors": [
    { "field": "email", "message": "must not be blank" },
    { "field": "initialBalance", "message": "must be greater than or equal to 0" }
  ]
}
```

Override in `application.yml` (all services):
```yaml
server:
  error:
    include-message: always
    include-binding-errors: never    # handled manually by GlobalExceptionHandler
```

### 4.2 Standard HTTP Status Code Contract

Binding for **all services** — no deviations without a decision record.

| Scenario | Status |
|---|---|
| Successful creation | `201 Created` |
| Successful read / update | `200 OK` |
| Successful delete | `204 No Content` |
| Validation failure (bean validation) | `400 Bad Request` |
| Business rule violation (insufficient funds, etc.) | `422 Unprocessable Entity` |
| Authentication failure (bad/missing JWT) | `401 Unauthorized` |
| Authorization failure (accessing another user's resource) | `403 Forbidden` |
| Resource not found | `404 Not Found` |
| Idempotency key conflict (duplicate submission) | `409 Conflict` |
| Downstream service unavailable (circuit open) | `503 Service Unavailable` |

### 4.3 Pagination Envelope

Spring's native `Page<T>` JSON serialization. No custom wrapper.

```json
{
  "content": [...],
  "pageable": { "pageNumber": 0, "pageSize": 20, "sort": { "sorted": true } },
  "totalElements": 150,
  "totalPages": 8,
  "last": false,
  "first": true,
  "size": 20,
  "number": 0
}
```

Request params: `?page=0&size=20&sort=createdAt,desc`
Max page size: **100** (enforced globally via `spring.data.web.pageable.max-page-size=100`).

---

## 5. JWT Specification

| Property | Value |
|---|---|
| Algorithm | HMAC-SHA256 (`HS256`) |
| Issuer (`iss`) | `wisewallet` |
| Access token TTL | **15 minutes** |
| Refresh token TTL | **7 days** |
| Signing key storage (local) | `JWT_SECRET` environment variable from `.env` file |
| Signing key storage (prod) | AWS Secrets Manager path `/wisewallet/{env}/jwt` → `{"secret":"..."}` |
| Min key length | 32 characters — enforced at startup via `@PostConstruct` assertion |

### 5.1 Access Token Claims

| Claim | Type | Content |
|---|---|---|
| `sub` | `string` | `userId` UUID |
| `iat` | `number` | Issued-at (epoch seconds) |
| `exp` | `number` | Expiry (epoch seconds) |
| `roles` | `string[]` | e.g., `["ROLE_USER"]` |
| `accountIds` | `string[]` | UUIDs of user's bank accounts |

> **Consequence of embedding `accountIds`:** If a user opens or closes an account, their
> existing access tokens remain valid until expiry (up to 15 minutes). This is acceptable
> given the short TTL. Refresh token issuance always re-queries the DB and issues a fresh
> access token with current account IDs.

### 5.2 Token Flow (high level — detail deferred to Account Service plan)

```
POST /api/auth/login
  → Account Service validates credentials
  → Issues access token (15 min) + refresh token (7 days)
  → Refresh token stored in DB (hashed), not in JWT itself

POST /api/auth/refresh
  → Validates refresh token against DB (checks hash + expiry + not revoked)
  → Issues new access token + rotates refresh token

POST /api/auth/logout
  → Marks refresh token as revoked in DB
```

JWT validation on every protected request happens at the **Gateway** — downstream services receive a pre-validated `X-User-Id` and `X-Account-Ids` header, not the raw JWT.

---

## 6. Local Docker Compose Topology

All files in `wisewallet-infra/docker-compose/`.

### 6.1 Compose Profiles

| Profile | Containers | Use case |
|---|---|---|
| `core` | All 4 Postgres, Redis, Kafka, `kafka-init` | Minimum — every service dev session |
| `messaging` | Kafka UI, LocalStack, `localstack-init` | Notification Service or Kafka flows |
| `observability` | Jaeger, Elasticsearch, Logstash, Kibana, Prometheus, Grafana | Tracing, logging, metrics work |
| `full` | Everything | Pre-PR verification |

Usage:
```bash
docker compose --profile core up -d
docker compose --profile full up -d
```

### 6.2 Network

Single custom bridge network: **`wisewallet-net`**. All containers attached. Services reference each other by container name.

### 6.3 Container Inventory

#### Core profile

| Container name | Image | Host ports | Purpose |
|---|---|---|---|
| `postgres-account` | `pgvector/pgvector:pg16` | `5432:5432` | Account Service DB |
| `postgres-transaction` | `pgvector/pgvector:pg16` | `5433:5432` | Transaction Service DB |
| `postgres-notification` | `postgres:16-alpine` | `5434:5432` | Notification Service DB |
| `postgres-advisor` | `pgvector/pgvector:pg16` | `5435:5432` | Advisor Service DB (pgvector required) |
| `redis` | `redis:7-alpine` | `6379:6379` | Gateway rate limiting + Advisor session cache |
| `kafka` | `confluentinc/cp-kafka:7.6.0` | `9092:9092`, `29093:29093` | KRaft broker + controller |
| `kafka-init` | `confluentinc/cp-kafka:7.6.0` (one-shot) | — | Creates topics then exits |

#### Messaging profile

| Container name | Image | Host ports | Purpose |
|---|---|---|---|
| `kafka-ui` | `provectuslabs/kafka-ui:latest` | `8090:8080` | Kafka topic/consumer group browser |
| `localstack` | `localstack/localstack:3.8` | `4566:4566` | AWS SNS + SQS emulation |
| `localstack-init` | `amazon/aws-cli:latest` (one-shot) | — | Creates SNS topics + SQS queues then exits |

#### Observability profile

| Container name | Image | Host ports | Purpose |
|---|---|---|---|
| `jaeger` | `jaegertracing/all-in-one:1.65` | `16686:16686` (UI), `4317:4317` (OTLP gRPC), `4318:4318` (OTLP HTTP) | Distributed tracing |
| `elasticsearch` | `docker.elastic.co/elasticsearch/elasticsearch:8.17.0` | `9200:9200` | Log store |
| `logstash` | `docker.elastic.co/logstash/logstash:8.17.0` | `5000:5000/tcp`, `5044:5044` | Log pipeline |
| `kibana` | `docker.elastic.co/kibana/kibana:8.17.0` | `5601:5601` | Log browser |
| `prometheus` | `prom/prometheus:v3.2.1` | `9090:9090` | Metrics scraping |
| `grafana` | `grafana/grafana:11.6.0` | `3000:3000` | Dashboards |

### 6.4 Kafka KRaft Environment Variables

| Variable | Value |
|---|---|
| `KAFKA_NODE_ID` | `1` |
| `KAFKA_PROCESS_ROLES` | `broker,controller` |
| `KAFKA_CONTROLLER_QUORUM_VOTERS` | `1@kafka:29093` |
| `KAFKA_LISTENERS` | `PLAINTEXT://kafka:29092,CONTROLLER://kafka:29093,EXTERNAL://0.0.0.0:9092` |
| `KAFKA_ADVERTISED_LISTENERS` | `PLAINTEXT://kafka:29092,EXTERNAL://localhost:9092` |
| `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP` | `CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT` |
| `KAFKA_INTER_BROKER_LISTENER_NAME` | `PLAINTEXT` |
| `CLUSTER_ID` | `<pre-generated UUID — hardcoded in file>` |

`CLUSTER_ID` must be generated once (`kafka-storage random-uuid`) and hardcoded for idempotent restarts.

### 6.5 Kafka Topics (local partition counts)

| Topic name | Partitions (local) | Notes |
|---|---|---|
| `account.created` | 1 | Low volume |
| `account.balance-low` | 1 | Low volume |
| `txn.created` | 3 | Higher volume |
| `txn.categorized` | 3 | Higher volume |
| `alert.triggered` | 1 | Low volume |

Production partition counts deferred to Transaction Service planning step.

### 6.6 LocalStack Resources (created by `localstack-init`)

```
SNS topic:     wisewallet-notifications
SQS queues:    wisewallet-email
               wisewallet-push
               wisewallet-webhook
               wisewallet-dlq
SNS subs:      wisewallet-notifications → wisewallet-email   (all message types)
               wisewallet-notifications → wisewallet-push    (all message types)
               wisewallet-notifications → wisewallet-webhook (all message types)
```

Filter policies per queue: deferred to Notification Service planning step.

### 6.7 Health Checks

Standard settings for all containers: `interval: 10s`, `timeout: 5s`, `retries: 5`, `start_period: 30s`.

| Container | Health command |
|---|---|
| `postgres-*` | `pg_isready -U postgres` |
| `redis` | `redis-cli ping` |
| `kafka` | `kafka-topics.sh --bootstrap-server localhost:9092 --list` |
| `localstack` | `curl -f http://localhost:4566/_localstack/health` |
| `elasticsearch` | `curl -f http://localhost:9200/_cluster/health?wait_for_status=yellow` |
| `jaeger` | `wget -q -O /dev/null http://localhost:16686/` |
| `kibana` | `curl -f http://localhost:5601/api/status` |

### 6.8 Startup `depends_on` Order

```
kafka-init       → depends_on: kafka        (condition: healthy)
kafka-ui         → depends_on: kafka        (condition: healthy)
localstack-init  → depends_on: localstack   (condition: healthy)
logstash         → depends_on: elasticsearch (condition: healthy)
kibana           → depends_on: elasticsearch (condition: healthy)
grafana          → depends_on: prometheus   (condition: started)
```

Application containers (in `docker-compose.override.yml`) depend on their `postgres-{service}` (healthy) + `kafka` (healthy) + optionally `redis` (healthy).

### 6.9 Named Volumes

| Volume name | Used by |
|---|---|
| `pgdata-account` | `postgres-account` |
| `pgdata-transaction` | `postgres-transaction` |
| `pgdata-notification` | `postgres-notification` |
| `pgdata-advisor` | `postgres-advisor` |
| `esdata` | `elasticsearch` |
| `prometheus-data` | `prometheus` |
| `grafana-data` | `grafana` |

### 6.10 Bind Mounts (from infra repo)

| Host path | Container path | Container |
|---|---|---|
| `./prometheus.yml` | `/etc/prometheus/prometheus.yml` | `prometheus` |
| `./logstash/pipeline.conf` | `/usr/share/logstash/pipeline/logstash.conf` | `logstash` |
| `./grafana/dashboards/` | `/etc/grafana/provisioning/dashboards/` | `grafana` |

---

## 7. Environment & Configuration Strategy

### 7.1 Profile Hierarchy & Priority

```
1. application.yml              ← base defaults, commit-safe, no secrets
2. application-{profile}.yml   ← profile overrides
3. Environment variables        ← highest priority, used at runtime
```

`SPRING_PROFILES_ACTIVE` controls the active profile.

| Profile | Set by | Target |
|---|---|---|
| `local` | `.env` file → IDE run config | Developer laptop |
| `dev` | ECS task definition env var | AWS dev environment |
| `prod` | ECS task definition env var | AWS production |
| `test` | `@ActiveProfiles("test")` in tests | Testcontainers ITs |

### 7.2 What Each File Contains

**`application.yml`** (committed, no secrets):
- Kafka topic names and consumer group IDs
- Liquibase changelog path
- JPA settings (`ddl-auto: validate`)
- Actuator config (see Section 8.5)
- Logging levels (`com.wisewallet.*: INFO` in base)
- OTel service name
- Pagination max page size: `100`
- Management server port

**`application-local.yml`** (committed — uses safe placeholder defaults):
- `spring.datasource.url` → `localhost:{service-port}`
- `spring.datasource.password: ${DB_PASSWORD:dev_password}`
- `spring.kafka.bootstrap-servers: localhost:9092`
- `spring.data.redis.host: localhost`
- `OTEL_EXPORTER_OTLP_ENDPOINT: http://localhost:4317`
- `spring.threads.virtual.enabled: true` (only in applicable services)
- `spring.cloud.aws.endpoint: http://localhost:4566` (only in notification-service)
- `spring.cloud.aws.secretsmanager.enabled: false`

**`application-dev.yml`** and **`application-prod.yml`** (committed — reference env vars only):
- `spring.datasource.url: ${DB_URL}`
- `spring.datasource.username: ${DB_USERNAME}`
- `spring.datasource.password: ${DB_PASSWORD}`
- `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}`
- `jwt.secret: ${JWT_SECRET}`

**`application-test.yml`** (committed):
- `spring.cloud.aws.credentials.instance-profile: false`
- DS URL + credentials managed by `@DynamicPropertySource` (Testcontainers)

### 7.3 `.env` File Convention

- Location: **service repo root** (for IDE runs) and **`wisewallet-infra/docker-compose/.env`** (for Compose infra passwords)
- **Never committed** — `.gitignore` includes: `.env`, `*.env`, `!.env.example`
- `.env.example` is committed with placeholder values

**`.env.example` per service (account-service example):**
```
SPRING_PROFILES_ACTIVE=local
DB_PASSWORD=dev_password
JWT_SECRET=local-dev-secret-replace-this-needs-32chars!
```

**`wisewallet-infra/docker-compose/.env.example`:**
```
POSTGRES_PASSWORD=dev_password
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
GRAFANA_ADMIN_PASSWORD=admin
```

### 7.4 AWS Secrets Manager (prod only)

**Library:** `spring-cloud-aws-starter-secrets-manager` (included only in service deps, fetched before `ApplicationContext` refresh).

**Secret naming convention:**

| Secret path | JSON payload keys |
|---|---|
| `/wisewallet/{env}/{service}/datasource` | `url`, `username`, `password` |
| `/wisewallet/{env}/jwt` | `secret` |
| `/wisewallet/{env}/openai` | `api-key` |
| `/wisewallet/{env}/kafka` | `bootstrap-servers` |

Spring Cloud AWS auto-maps secret keys to `spring.datasource.*` etc. via prefix configuration.

Disabled locally:
```yaml
spring:
  cloud:
    aws:
      secretsmanager:
        enabled: false
```

---

## 8. Cross-Cutting Code — Duplication Map

No shared JAR. Each artifact below is **duplicated** into the relevant service repos.
This plan is the canonical specification for what each duplicated piece must do.

### 8.1 Duplication Inventory

| Artifact | Class name | Package | Present in |
|---|---|---|---|
| Correlation ID HTTP filter | `CorrelationIdFilter` | `com.wisewallet.{svc}.config.filter` | account, transaction, notification, advisor |
| Correlation ID Kafka producer interceptor | `KafkaCorrelationIdProducerInterceptor` | `com.wisewallet.{svc}.config.kafka` | transaction |
| Correlation ID Kafka consumer extractor | `KafkaCorrelationIdConsumerHelper` | `com.wisewallet.{svc}.config.kafka` | notification, advisor |
| Global exception handler | `GlobalExceptionHandler` | `com.wisewallet.{svc}.config.exception` | account, transaction, advisor |
| Business rule base exception | `BusinessRuleException` | `com.wisewallet.{svc}.exception` | account, transaction, notification, advisor |
| Logback config | `logback-spring.xml` | `src/main/resources/` | all 6 repos |
| OTel agent Dockerfile download step | `ARG` + `ADD` lines | `docker/Dockerfile` | all 5 service repos |
| Actuator + server error config block | `application.yml` section | `src/main/resources/` | all 6 repos |

### 8.2 `CorrelationIdFilter` — Specification

Package: `com.wisewallet.{service}.config.filter`
Extends: `OncePerRequestFilter`
Bean name: `correlationIdFilter`

Logic:
1. Read `X-Correlation-ID` from `HttpServletRequest` headers
2. If absent → generate `UUID.randomUUID().toString()`
3. `MDC.put("correlationId", correlationId)`
4. Add to response: `response.setHeader("X-Correlation-ID", correlationId)`
5. `filterChain.doFilter(request, response)`
6. `finally`: `MDC.remove("correlationId")` — prevents MDC leaks across thread pool reuse

The Gateway applies this filter first, so downstream services will almost always find the header pre-populated.

### 8.3 `GlobalExceptionHandler` — Specification

Package: `com.wisewallet.{service}.config.exception`
Annotation: `@RestControllerAdvice`

Required handler methods:

| Exception | HTTP Status | Notes |
|---|---|---|
| `MethodArgumentNotValidException` | `400` | Populate `errors` array with field + message pairs |
| `ConstraintViolationException` | `400` | Same `errors` structure |
| `NoSuchElementException` + `*NotFoundException` | `404` | — |
| `BusinessRuleException` subclasses | `422` | — |
| `AccessDeniedException` | `403` | — |
| `AuthenticationException` | `401` | — |
| `Exception` (catch-all) | `500` | Log at ERROR; return generic message — never expose stack traces |

### 8.4 `BusinessRuleException` — Specification

Package: `com.wisewallet.{service}.exception`
Extends: `RuntimeException`
Fields: `message`

Each service defines its own named subclasses:
- `account-service`: `UserAlreadyExistsException`, `AccountNotFoundException`, `InvalidCredentialsException`
- `transaction-service`: `InsufficientFundsException`, `AccountNotFoundException`, `TransactionNotFoundException`, `DuplicateIdempotencyKeyException`
- `notification-service`: `NotificationNotFoundException`
- `advisor-service`: `AdvisorSessionNotFoundException`

---

## 9. Observability Bootstrap

### 9.1 OpenTelemetry Java Agent

**Attachment method:** JVM flag only — no application code changes.

```
-javaagent:/app/opentelemetry-javaagent.jar
```

**Agent version:** `2.12.0`

Dockerfile download URL (pinned):
```
https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.12.0/opentelemetry-javaagent.jar
```

Use `ARG OTEL_AGENT_VERSION=2.12.0` in Dockerfile. Never use a redirect URL — pin to a specific release.

**Auto-instrumented by the agent (no code needed):**
Spring MVC, Spring WebFlux, JDBC/Hibernate, Spring Kafka (producer + consumer), RestTemplate, Feign clients, Redis.

### 9.2 OTel Agent Environment Variables

| Variable | Local value | Prod value |
|---|---|---|
| `OTEL_SERVICE_NAME` | `account-service` (etc.) | same |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://jaeger:4317` | OTel Collector URL (TBD in infra plan) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | `grpc` |
| `OTEL_TRACES_EXPORTER` | `otlp` | `otlp` |
| `OTEL_METRICS_EXPORTER` | `none` | `none` (Micrometer → Prometheus handles metrics) |
| `OTEL_LOGS_EXPORTER` | `none` | `none` (Logback → Logstash handles logs) |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` | same |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment=local` | `deployment.environment=prod` |

### 9.3 Correlation ID vs. W3C Trace Context — Two Parallel IDs

Both are in play simultaneously:

| ID | Header | Generated by | Purpose |
|---|---|---|---|
| `traceId` (128-bit) | `traceparent` (W3C) | OTel agent automatically | Distributed trace in Jaeger; spans across services |
| Correlation ID (UUID) | `X-Correlation-ID` | Gateway `CorrelationIdFilter` | Human-readable ID in log searches; returned in error responses |

Both appear in every log line via MDC.

**`X-Correlation-ID` across HTTP:**
```
Client → Gateway (generates X-Correlation-ID if absent)
       → Downstream services (read header → MDC → forward)
       ← Response includes X-Correlation-ID
```

**`X-Correlation-ID` across Kafka:**
- **Producer:** read `MDC.get("correlationId")` → set as Kafka record header `X-Correlation-ID`
- **Consumer:** read `ConsumerRecord.headers().lastHeader("X-Correlation-ID")` → `MDC.put("correlationId", value)`

### 9.4 MDC Key Reference

| MDC key | Populated by | Scope |
|---|---|---|
| `correlationId` | `CorrelationIdFilter` / Kafka consumer helper | All request-scoped logs |
| `traceId` | OTel agent Logback MDC bridge (automatic) | All logs |
| `spanId` | OTel agent Logback MDC bridge (automatic) | All logs |
| `userId` | Security context reader (post-JWT validation) | Authenticated requests only |

### 9.5 Logback Configuration (`logback-spring.xml`)

One file per service repo at `src/main/resources/logback-spring.xml`.
Uses Spring profile conditionals (`<springProfile name="local">` / `<springProfile name="dev,prod">`).

**`local` profile — human-readable console:**
- Appender: `ConsoleAppender`
- Pattern: `%d{HH:mm:ss.SSS} %-5level [%-15thread] %logger{36} [corrId=%X{correlationId} traceId=%X{traceId}] - %msg%n`
- Root level: `INFO`; `com.wisewallet.*`: `DEBUG`

**`dev` + `prod` profiles — JSON console (Logstash/ECS agent picks up stdout):**
- Appender: `ConsoleAppender` with `LogstashEncoder` (from `logstash-logback-encoder:8.0`)
- Automatic JSON fields: `timestamp`, `level`, `logger_name`, `message`, `thread_name`
- MDC fields auto-appended: `correlationId`, `traceId`, `spanId`, `userId`
- Root level: `WARN`; `com.wisewallet.*`: `INFO`

**Why console instead of TCP to Logstash?**
Resilience — if Logstash is down, logs still land on stdout (captured by Docker/ECS). Logstash consumes container stdout via the Beats agent.

### 9.6 Spring Boot Actuator — Baseline Configuration (`application.yml`, all services)

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:8090}   # each service uses its own port (listed in Section 10)
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always          # required for ECS liveness/readiness probes
  metrics:
    export:
      prometheus:
        enabled: true
```

> **Security note:** The Gateway must **explicitly block** forwarding of any path
> matching `/actuator/**` to prevent internal endpoints being internet-accessible.

### 9.7 Prometheus Scrape Config

In `wisewallet-infra/docker-compose/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: gateway-service
    static_configs:
      - targets: ['gateway-service:8090']
    metrics_path: /actuator/prometheus

  - job_name: account-service
    static_configs:
      - targets: ['account-service:8091']
    metrics_path: /actuator/prometheus

  - job_name: transaction-service
    static_configs:
      - targets: ['transaction-service:8092']
    metrics_path: /actuator/prometheus

  - job_name: notification-service
    static_configs:
      - targets: ['notification-service:8093']
    metrics_path: /actuator/prometheus

  - job_name: advisor-service
    static_configs:
      - targets: ['advisor-service:8094']
    metrics_path: /actuator/prometheus
```

---

## 10. Service Application Ports

| Service | App port | Management port |
|---|---|---|
| `gateway-service` | `8080` | `8090` |
| `account-service` | `8081` | `8091` |
| `transaction-service` | `8082` | `8092` |
| `notification-service` | `8083` | `8093` |
| `advisor-service` | `8084` | `8094` |

---

## 11. Dockerfile Template (Canonical)

Two-stage build. Each service repo has `docker/Dockerfile`.

**Stage 1 — Build** (base: `eclipse-temurin:21-jdk-alpine`):
1. Copy Gradle wrapper files and build scripts
2. Run `./gradlew dependencies --no-daemon` (cache this layer — only re-runs when `build.gradle.kts` changes)
3. Copy `src/`
4. Run `./gradlew bootJar --no-daemon -x test`

**Stage 2 — Runtime** (base: `eclipse-temurin:21-jre-alpine`):
1. Create non-root user and group: `wisewallet` — never run as root
2. `ARG OTEL_AGENT_VERSION=2.12.0`
3. Download OTel agent from pinned GitHub Release URL → `/app/otel-agent.jar`
4. Copy JAR from build stage → `/app/app.jar`
5. `USER wisewallet`
6. `EXPOSE {app-port} {management-port}`
7. `ENTRYPOINT ["java", "${JAVA_OPTS}", "-javaagent:/app/otel-agent.jar", "-jar", "/app/app.jar"]`

**`JAVA_OPTS` defaults** (set in Docker Compose `environment` block, overrideable in ECS):
```
-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom
```

Use `-XX:MaxRAMPercentage` (not `-Xmx`) so the same image works across different ECS task sizes.

---

## 12. Git & PR Conventions

### 12.1 Branch Strategy (Gitflow — simplified)

Applies identically to all 6 repos.

| Branch | Purpose | Protected | Merge target | Deploy |
|---|---|---|---|---|
| `main` | Production-ready only | Yes (PR + 1 approval + green CI) | — | AWS prod |
| `develop` | Integration branch | Yes (PR + green CI) | `main` via release | AWS dev |
| `feature/{scope}/{slug}` | New work | No | `develop` | — |
| `bugfix/{scope}/{slug}` | Bug fixes | No | `develop` | — |
| `release/{version}` | Release stabilization | No | `main` + `develop` | — |
| `hotfix/{slug}` | Emergency prod fix | No | `main` + `develop` | — |

**Merge rules:**
- `feature/*` → `develop`: **squash merge** (linear history)
- `develop` → `main`: **merge commit** (preserve release boundary)
- Tags: `v{major}.{minor}.{patch}` on every merge to `main`

### 12.2 Conventional Commits

Format: `<type>(<scope>): <short imperative description>`

| Type | Use for |
|---|---|
| `feat` | New feature or endpoint |
| `fix` | Bug fix |
| `test` | Adding or updating tests |
| `refactor` | Code change with no behavior change |
| `chore` | Build or dependency updates |
| `docs` | README, comments, API docs |
| `ci` | GitHub Actions workflow changes |
| `build` | Gradle config changes |
| `perf` | Performance improvements |

Scopes: `account`, `transaction`, `notification`, `advisor`, `gateway`, `infra`

Examples:
```
feat(account): implement user registration endpoint
fix(transaction): handle concurrent transfer race condition
chore(deps): bump spring-boot to 3.4.4
test(notification): add Testcontainers IT for Kafka consumer
```

Enforcement: `commitlint` GH Actions step on all PRs. Full workflow YAML deferred to CI/CD planning step.

### 12.3 PR Template (`.github/pull_request_template.md` — all repos)

```markdown
## What this PR does
<!-- 1–2 sentence summary -->

## Type of change
- [ ] Feature
- [ ] Bug fix
- [ ] Refactor
- [ ] Infrastructure / config
- [ ] Documentation

## Checklist
- [ ] Unit tests added / updated
- [ ] Integration tests added / updated (if DB or Kafka touched)
- [ ] Liquibase migration added (if schema changed)
- [ ] `application.yml` updated for any new config keys
- [ ] `.env.example` updated for any new environment variables
- [ ] No secrets, tokens, or passwords committed
- [ ] Actuator management port NOT routed through Gateway (if routing changed)
- [ ] `X-Correlation-ID` propagated through any new Kafka producers/consumers

## How to test locally
<!-- Steps for the reviewer -->

## Linked issues
Closes #
```

---

## 13. Skeleton Bootstrap Work Order

| Step | Task | Output |
|---|---|---|
| 1 | Create all 6 GitHub repos under the org; set branch protections on `main` and `develop` | 6 empty repos |
| 2 | Set up `wisewallet-infra` repo — Docker Compose `core` profile only | `docker compose --profile core up -d` succeeds |
| 3 | Bootstrap `account-service` Gradle project (Kotlin DSL, version catalog, Lombok + MapStruct wiring, Liquibase, no business logic yet) | Compiles; `bootRun` connects to `postgres-account` |
| 4 | Add `logback-spring.xml` + `CorrelationIdFilter` + `GlobalExceptionHandler` + Actuator config to `account-service` | Observability skeleton in place |
| 5 | Add OTel agent to `account-service` Dockerfile; build image | Traces appear in Jaeger |
| 6 | Repeat steps 3–5 for remaining 4 services | All 5 skeletons compile and connect to their DBs |
| 7 | Add `observability` Compose profile to infra repo | Full local stack starts: Jaeger, ELK, Prometheus, Grafana all healthy |
| 8 | Add `messaging` Compose profile to infra repo; verify Kafka UI and LocalStack | Topics visible in Kafka UI; `aws --endpoint-url=http://localhost:4566 sns list-topics` returns correctly |
| 9 | Verify Prometheus scrapes all 5 `/actuator/prometheus` endpoints | Grafana shows service metrics |
| 10 | Add PR templates and `.env.example` files to all repos | Foundation complete |

At this point the skeleton is fully operational. Service-by-service feature development
begins with the **Account Service** (auth + CRUD).

---

## 14. Next Steps

The following service plans are pending. Open questions from Section "Open / Deferred Questions"
must be resolved at the start of each service planning session.

| Order | Service | Blocked by deferred questions |
|---|---|---|
| 1 | **Account Service** | S1 (locking), S2 (Feign vs. gRPC), S4 (Kafka consumer role) |
| 2 | **API Gateway** | S2 (depends on Account Service auth contract) |
| 3 | **Transaction Service** | S2 (Feign vs. gRPC), S5 (withdraw endpoint) |
| 4 | **Notification Service** | None (SNS/SQS filter policies TBD but non-blocking) |
| 5 | **AI Advisor Service** | S3 (Spring AI vs. LangChain4j) |
| 6 | **Infrastructure (Terraform + CI/CD)** | All service plans complete |
