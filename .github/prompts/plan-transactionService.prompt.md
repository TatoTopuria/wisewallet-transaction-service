# WiseWallet — Transaction Service Plan

> **Depends on:** `00-foundation-plan.md` and the Account Service plan (including amendments in §0 below).
> All cross-cutting conventions (Liquibase, Lombok, MapStruct, virtual threads, Kafka outbox,
> error model, pagination, correlation ID, OTel) are inherited from the foundation plan. This
> document does not repeat them.

---

## Confirmed Decisions

| # | Topic | Decision |
|---|---|---|
| Arch | Balance concurrency | **Option C — Balance reservation** pattern on Account Service |
| C4 | Account Service Kafka consumer | **Superseded** — Account Service no longer consumes `txn.created`; balance updates are synchronous |
| 1 | Transaction status lifecycle | `PENDING → DEBITED → COMPLETED \| FAILED` |
| 2 | Transfer records | **Two records** (DEBIT leg + CREDIT leg) linked by `transfer_id` UUID |
| 3 | Description/reference field | **None** |
| 4 | Amount sign convention | **Signed** — negative for debits, positive for credits |
| 5 | Idempotency key source | **Client-supplied** `Idempotency-Key` request header |
| 6 | Idempotency key TTL | **48 hours** |
| 7 | Category enum | `GROCERIES`, `UTILITIES`, `ENTERTAINMENT`, `DINING`, `TRANSPORT`, `HEALTHCARE`, `SHOPPING`, `TRAVEL`, `INCOME`, `TRANSFER`, `OTHER` |
| 8 | Rule mechanism | **MCC first, keyword fallback** — keyword fallback degrades to `OTHER` in V1 (no text field) |
| 9 | User category override | **Yes** — `PUT /api/transactions/{id}/category` endpoint |
| 10 | Saga style | **Choreography** — no central orchestrator |
| 11 | Debit-succeeds/credit-fails | **Automatic reversal** via reservation release → both legs → `FAILED` |
| 12 | Transfer scope | **Same-user only** — both accounts must be in `X-Account-Ids` JWT claim |
| 13 | Filters on `GET /api/transactions` | `accountId`, `from`/`to` (ISO 8601), `category` (multi-value), `type`, `minAmount`/`maxAmount` (absolute), `status` |
| 14 | Default page size | **10** |
| 15 | Sort fields | `createdAt` (default DESC), `amount` (absolute value) |
| 16 | Monthly summary shape | Confirmed (see §5.6) |
| 17 | Monthly summary scope | **Aggregated across all user's accounts** |
| 18 | Historical depth | **Last 12 months** |
| 19 | Production partitions | **6** for `txn.created` and `txn.categorized` |
| 20 | `txn.categorized` key | **`accountId`** |
| 21 | Transaction Service Kafka role | **Purely a producer** |
| 22 | INACTIVE account ops | **Blocked — 422** for both deposit and withdrawal |
| 23 | CLOSED destination | **422 immediately** (sync check via Feign before saga starts) |
| 24 | Transaction amount limits | **None for V1** |
| 25 | Same-account transfer | **Blocked — 422** |

**Architect's calls:**
- `amount` in requests always positive (absolute); Transaction Service applies sign at persistence
- `minAmount`/`maxAmount` filter on `ABS(amount)` — intuitive for end users
- Idempotency key: composite unique `(key, user_id)`; separate `idempotency_keys` table with `expires_at`
- Reservation `expires_at` safety window: **10 minutes** — Account Service scheduled job releases expired reservations
- Feign timeout: connect 2s, read 5s on Account Service client
- `CategoryRuleEngine` built with strategy interface — keyword branch present but V1 falls through to `OTHER`

---

## 0. Account Service Plan Amendments (Apply Before Building Transaction Service)

Option C was chosen after the Account Service plan was written. These amendments must be applied.

### Amendment 1 — New Schema Additions

**Add to `accounts` table** (migration `006-add-reservation-columns.xml`):
- `reserved_amount NUMERIC(19,4) NOT NULL DEFAULT 0.0000`

**New `account_reservations` table** (same migration):

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | PK | |
| `account_id` | `UUID` | NOT NULL, FK → `accounts.id` | |
| `transaction_id` | `UUID` | NOT NULL | Supplied by Transaction Service |
| `amount` | `NUMERIC(19,4)` | NOT NULL, CHECK > 0 | Absolute value only |
| `status` | `VARCHAR(10)` | NOT NULL | CHECK IN (`ACTIVE`,`COMMITTED`,`RELEASED`) |
| `expires_at` | `TIMESTAMPTZ` | NOT NULL | `created_at + 10 minutes` |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | |

Index: `(account_id, status)` WHERE `status = 'ACTIVE'`

### Amendment 2 — New Internal Endpoints (Add to `InternalAccountController`)

| Method + Path | Description |
|---|---|
| `POST /internal/accounts/{id}/debit` | Atomic `balance -= amount` using `@Version`. 422 if insufficient balance or account not ACTIVE. Returns `InternalAccountResponse`. |
| `POST /internal/accounts/{id}/credit` | Atomic `balance += amount` using `@Version`. 422 if account not ACTIVE. Returns `InternalAccountResponse`. |
| `POST /internal/accounts/{id}/reserve` | Creates `account_reservation` ACTIVE, adds to `reserved_amount`. Returns `reservationId` + `availableBalance`. 422 if `balance - reserved_amount < amount`. |
| `POST /internal/accounts/{id}/commit` | Marks reservation COMMITTED, applies `balance -= amount`, `reserved_amount -= amount`. 422 if reservation not found/wrong status. |
| `POST /internal/accounts/{id}/release` | Marks reservation RELEASED, applies `reserved_amount -= amount`. 422 if reservation not found/wrong status. |

Each uses `@Version` optimistic lock on `Account`. `OptimisticLockException` → HTTP `409 Conflict`.

**Updated `InternalAccountResponse`** adds field: `availableBalance` (`balance - reserved_amount`).

### Amendment 3 — Removed Component

Remove `TransactionCreatedConsumer` from Account Service. C4 decision is superseded by Option C.

`BalanceService` checks `balance <= balanceLowThreshold` **synchronously** after every debit/commit and writes `account.balance-low` to outbox in the same transaction.

### Amendment 4 — New Scheduled Job

`ReservationCleanupService` (`@Scheduled(fixedDelay = 60_000)`) in Account Service:
- Query: `SELECT * FROM account_reservations WHERE status = 'ACTIVE' AND expires_at < now()`
- For each expired row: release logic (decrement `reserved_amount`, mark `RELEASED`)
- Log at WARN level — expired reservations indicate Transaction Service failures

---

## 1. Package Structure

```
com.wisewallet.transaction
├── TransactionServiceApplication.java
│
├── controller/
│   ├── TransactionController.java        POST /deposit|withdraw|transfer; GET /; GET /summary/monthly
│   └── CategoryController.java           PUT /api/transactions/{id}/category
│
├── service/
│   ├── DepositService.java               Deposit flow, idempotency check
│   ├── WithdrawalService.java            Withdrawal flow, idempotency check
│   ├── TransferService.java              Transfer saga coordinator
│   ├── TransactionQueryService.java      Filtered list, monthly aggregation
│   ├── CategoryService.java              Auto-categorize + user override
│   ├── IdempotencyService.java           Insert, check, complete idempotency key records
│   └── OutboxPublisherService.java       Poller: outbox → Kafka
│
├── domain/
│   ├── Transaction.java                  @Entity — transactions table
│   ├── IdempotencyKey.java               @Entity — idempotency_keys table
│   ├── OutboxEvent.java                  @Entity — txn_outbox table
│   ├── TransactionStatus.java            enum PENDING, DEBITED, COMPLETED, FAILED
│   ├── TransactionType.java              enum DEPOSIT, WITHDRAWAL, TRANSFER
│   └── TransactionCategory.java         enum (all 11 categories)
│
├── repository/
│   ├── TransactionRepository.java        JpaRepository + JpaSpecificationExecutor
│   ├── IdempotencyKeyRepository.java     JpaRepository<IdempotencyKey, UUID>
│   └── OutboxEventRepository.java        JpaRepository<OutboxEvent, UUID>
│
├── dto/
│   ├── request/
│   │   ├── DepositRequest.java           record
│   │   ├── WithdrawalRequest.java        record
│   │   ├── TransferRequest.java          record
│   │   └── UpdateCategoryRequest.java    record
│   └── response/
│       ├── TransactionResponse.java      record
│       ├── CategorySummary.java          record  (category, total, transactionCount)
│       └── MonthlySummaryResponse.java   record
│
├── mapper/
│   └── TransactionMapper.java            MapStruct: Transaction → TransactionResponse
│
├── event/
│   ├── TransactionCreatedEvent.java      record (Kafka payload)
│   └── TransactionCategorizedEvent.java  record (Kafka payload)
│
├── client/
│   ├── AccountServiceClient.java         @FeignClient interface
│   ├── dto/
│   │   ├── ReserveRequest.java           record — amount, transactionId
│   │   ├── ReserveResponse.java          record — reservationId, availableBalance
│   │   ├── DebitCreditRequest.java       record — amount
│   │   └── InternalAccountResponse.java  record — mirrors Account Service's response
│   └── config/
│       └── AccountServiceFeignConfig.java  Timeout + X-Internal-Key interceptor bean
│
├── categorization/
│   ├── CategoryRuleEngine.java           Orchestrates strategies; returns TransactionCategory
│   ├── MccCategoryMapping.java           static Map<String, TransactionCategory>
│   └── KeywordCategoryMapping.java       V1 stub — always returns empty Optional
│
├── spec/
│   └── TransactionSpecification.java     JPA Specification factory for dynamic filters
│
├── config/
│   ├── KafkaConfig.java                  Producer bean config
│   ├── FeignConfig.java                  Global Feign defaults
│   └── filter/
│       └── CorrelationIdFilter.java      (duplicated per foundation plan)
│
└── exception/
    ├── BusinessRuleException.java
    ├── InsufficientFundsException.java
    ├── TransactionNotFoundException.java
    ├── DuplicateIdempotencyKeyException.java
    ├── AccountInactiveException.java
    ├── AccountNotFoundException.java
    ├── SameAccountTransferException.java
    └── GlobalExceptionHandler.java       @RestControllerAdvice (duplicated)
```

---

## 2. Database Schema

### 2.1 `transactions` Table

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | PK, `gen_random_uuid()` | |
| `user_id` | `UUID` | NOT NULL | Denormalized from JWT — no FK across service boundaries |
| `account_id` | `UUID` | NOT NULL | Primary account for this leg |
| `transfer_id` | `UUID` | NULLABLE | Links DEBIT + CREDIT legs of a transfer |
| `amount` | `NUMERIC(19,4)` | NOT NULL | Signed: negative=debit, positive=credit |
| `type` | `VARCHAR(20)` | NOT NULL | CHECK IN (`DEPOSIT`,`WITHDRAWAL`,`TRANSFER`) |
| `status` | `VARCHAR(10)` | NOT NULL | CHECK IN (`PENDING`,`DEBITED`,`COMPLETED`,`FAILED`) |
| `category` | `VARCHAR(20)` | NULLABLE | Set after categorization |
| `mcc_code` | `VARCHAR(10)` | NULLABLE | Client-supplied merchant category code |
| `idempotency_key` | `VARCHAR(255)` | NOT NULL | Client-supplied header value |
| `reservation_id` | `UUID` | NULLABLE | Account Service reservation ID (DEBIT leg of transfers only) |
| `is_deleted` | `BOOLEAN` | NOT NULL, default `false` | |
| `deleted_at` | `TIMESTAMPTZ` | NULLABLE | |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | |

**Indexes:**
- `INDEX (user_id, created_at DESC)` — primary list query
- `INDEX (account_id, created_at DESC)` — per-account filter
- `INDEX (user_id, status)` WHERE `is_deleted = false` — status filter
- `INDEX (transfer_id)` WHERE `transfer_id IS NOT NULL` — fetch both legs by transfer_id
- `INDEX (user_id, type, created_at)` WHERE `status = 'COMPLETED'` — monthly aggregation query
- `INDEX (account_id, category, created_at)` WHERE `status = 'COMPLETED'` — category aggregation

No FK constraint on `account_id` — cross-service boundary; ownership enforced via JWT claims.

---

### 2.2 `idempotency_keys` Table

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | PK | |
| `key` | `VARCHAR(255)` | NOT NULL | Client-supplied key value |
| `user_id` | `UUID` | NOT NULL | |
| `response_status` | `INT` | NULLABLE | Cached HTTP status; NULL while in-flight |
| `response_body` | `TEXT` | NULLABLE | Cached JSON response; NULL while in-flight |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | |
| `expires_at` | `TIMESTAMPTZ` | NOT NULL | `created_at + 48 hours` |

**Constraints:**
- `UNIQUE (key, user_id)` — idempotency scoped to user

**Indexes:**
- Unique index on `(key, user_id)` (serves as lookup index)
- `INDEX (expires_at)` WHERE `expires_at < now()` — cleanup job

---

### 2.3 `txn_outbox` Table

| Column | Type | Constraints |
|---|---|---|
| `id` | `UUID` | PK |
| `aggregate_id` | `UUID` | NOT NULL (`transactionId`) |
| `event_type` | `VARCHAR(100)` | NOT NULL |
| `payload` | `JSONB` | NOT NULL |
| `status` | `VARCHAR(10)` | NOT NULL, CHECK IN (`PENDING`,`SENT`,`FAILED`) |
| `created_at` | `TIMESTAMPTZ` | NOT NULL |
| `processed_at` | `TIMESTAMPTZ` | NULLABLE |
| `retry_count` | `INT` | NOT NULL, default `0` |

**Index:** `(status, created_at)` WHERE `status = 'PENDING'`

---

## 3. Liquibase Migrations

| Filename | Creates |
|---|---|
| `db.changelog-master.xml` | Root manifest |
| `001-create-transactions-table.xml` | `transactions` table + all indexes |
| `002-create-idempotency-keys-table.xml` | `idempotency_keys` table + unique constraint + cleanup index |
| `003-create-txn-outbox-table.xml` | `txn_outbox` table + index |

Each changeset: `author: wisewallet-migrations`, `runOnChange: false`, `failOnError: true`.
Liquibase default schema: `transaction` (maps to the `transaction` schema in RDS).

---

## 4. JPA Entities

### 4.1 `Transaction` Entity

- `@Entity @Table(name = "transactions", schema = "transaction")`
- `@Id @GeneratedValue(strategy = GenerationType.UUID)`
- `@Enumerated(EnumType.STRING)` on `type`, `status`, `category`
- `@Column(precision = 19, scale = 4)` on `amount`
- `@PreUpdate` sets `updatedAt = Instant.now()`
- No `@Version` — Transaction records are updated by a single writer (`TransferService`). Concurrent writes controlled by idempotency, not optimistic locking.

### 4.2 `IdempotencyKey` Entity

- `@Table(name = "idempotency_keys", uniqueConstraints = @UniqueConstraint(columnNames = {"key","user_id"}))`
- Fields: `UUID id`, `String key`, `UUID userId`, `Integer responseStatus`, `String responseBody`, `Instant createdAt`, `Instant expiresAt`

### 4.3 `OutboxEvent` Entity

Identical structure to Account Service `OutboxEvent` — see Account Service plan §4.3.

---

## 5. REST API Contracts

### 5.1 `POST /api/transactions/deposit`

**Headers:** `X-User-Id`, `X-Account-Ids` (forwarded from Gateway), `Idempotency-Key: {uuid}` (Required)

**Request body:** `DepositRequest`
```
accountId   UUID        Required — must be in X-Account-Ids
amount      BigDecimal  Required, > 0, precision max 19 scale 4
mccCode     String      Optional, max 10 chars
```

**Validation:**
- `@NotNull` on `accountId`, `amount`
- `@DecimalMin("0.01")` on `amount`
- `accountId` must exist in `X-Account-Ids` header — 403 if not
- `Idempotency-Key` header: `@NotBlank`, max 255 chars

**Happy path (201 Created):**
1. `IdempotencyService.checkOrInsert(key, userId)` — if found-and-complete → return cached 201
2. Call `AccountServiceClient.credit(accountId, amount)` — 422 forwarded if account INACTIVE
3. Create `Transaction` (type=DEPOSIT, amount=+absolute, status=COMPLETED)
4. Run `CategoryRuleEngine.categorize(mccCode, null)` → category
5. Write `txn.created` + `txn.categorized` events to `txn_outbox` in same DB transaction
6. `IdempotencyService.complete(key, userId, 201, responseBody)`
7. Return `201` with `TransactionResponse`

**Response body:** `TransactionResponse`
```json
{
  "id": "uuid",
  "accountId": "uuid",
  "amount": "500.0000",
  "type": "DEPOSIT",
  "status": "COMPLETED",
  "category": "INCOME",
  "createdAt": "2026-04-08T10:00:00Z"
}
```

**Error cases:**

| Scenario | Status |
|---|---|
| Duplicate `Idempotency-Key` (in-flight) | `409 Conflict` |
| `accountId` not in user's `X-Account-Ids` | `403 Forbidden` |
| Account INACTIVE/CLOSED | `422 Unprocessable Entity` |
| Validation failure | `400 Bad Request` |
| Account Service unavailable (circuit open) | `503 Service Unavailable` |

---

### 5.2 `POST /api/transactions/withdraw`

**Headers:** same as deposit including `Idempotency-Key`

**Request body:** `WithdrawalRequest`
```
accountId   UUID        Required
amount      BigDecimal  Required, > 0
mccCode     String      Optional
```

**Happy path (201 Created):**
1. Idempotency check
2. Validate `accountId` in `X-Account-Ids` → 403 if not
3. Call `AccountServiceClient.debit(accountId, amount)` — atomic with `@Version` in Account Service
   - 422 if insufficient balance
   - 422 if INACTIVE/CLOSED
   - 409 if `OptimisticLockException` → retry up to 3 times with 100ms backoff in saga logic
4. Create `Transaction` (type=WITHDRAWAL, amount=–absolute, status=COMPLETED)
5. `CategoryRuleEngine.categorize(mccCode, null)` → category
6. Write outbox events
7. Complete idempotency key
8. Return `201`

**Error cases:** same as deposit + insufficient funds 422.

---

### 5.3 `POST /api/transactions/transfer`

**Headers:** same, `Idempotency-Key` required

**Request body:** `TransferRequest`
```
sourceAccountId       UUID        Required
destinationAccountId  UUID        Required
amount                BigDecimal  Required, > 0
```

**Pre-saga validation:**
- `sourceAccountId != destinationAccountId` → 422
- Both IDs must be in `X-Account-Ids` → 403 if either missing

**Happy path (201 Created) — Transfer Saga:**

```
Phase 1 — Setup [TX1]:
  - Idempotency check
  - Generate transfer_id = UUID.randomUUID()
  - Create DEBIT Transaction  (accountId=source, amount=–abs, type=TRANSFER, status=PENDING)
  - Create CREDIT Transaction (accountId=dest,   amount=+abs, type=TRANSFER, status=PENDING)
  - Flush both to DB

Phase 2 — Reserve [Feign]:
  - AccountServiceClient.reserve(sourceAccountId, amount, debitTxn.id)
  - On 422: [TX2] both → FAILED → failure outbox → return 422

Phase 3 — Mark DEBITED [TX2]:
  - DEBIT record → DEBITED, store reservationId

Phase 4 — Credit destination [Feign]:
  - AccountServiceClient.credit(destinationAccountId, amount)
  - On 422: → ROLLBACK path

Phase 5 — Commit reservation [Feign]:
  - AccountServiceClient.commit(sourceAccountId, reservationId)
  - On failure: → CRITICAL ALERT path (see note below)

Phase 6 — Complete [TX3]:
  - Both records → COMPLETED
  - Category: TRANSFER for both legs (bypasses CategoryRuleEngine)
  - Write txn.created + txn.categorized events (4 total) to txn_outbox
  - Complete idempotency key
  - Return 201
```

**Rollback path (Phase 4 failure):**
```
  - AccountServiceClient.release(sourceAccountId, reservationId)
  - [TX_R] both legs → FAILED
  - Write failure events to outbox
  - Complete idempotency key (422 status cached)
  - Return 422
```

> **Phase 5 failure (commit fails after credit):** Destination has been credited but source
> reservation not committed. Reservation auto-expires in 10 minutes (Account Service cleanup job
> releases it). For V1: log at ERROR with full context (transferId, reservationId, both accountIds),
> increment metric `transfer.commit.failure.count`, alert via monitoring. Manual investigation
> required. Compensation job is out of scope for V1.

**Response body:**
```json
{
  "transferId": "uuid",
  "transactions": [
    { "id": "uuid", "accountId": "uuid_source", "amount": "-150.0000", "type": "TRANSFER", "status": "COMPLETED", "category": "TRANSFER", "createdAt": "..." },
    { "id": "uuid", "accountId": "uuid_dest",   "amount": "+150.0000", "type": "TRANSFER", "status": "COMPLETED", "category": "TRANSFER", "createdAt": "..." }
  ]
}
```

**Error cases:**

| Scenario | Status |
|---|---|
| Same source and destination | `422 Unprocessable Entity` |
| Either account not in `X-Account-Ids` | `403 Forbidden` |
| Insufficient available balance | `422 Unprocessable Entity` |
| Destination account INACTIVE/CLOSED | `422 Unprocessable Entity` |
| Duplicate `Idempotency-Key` | `409 Conflict` |

---

### 5.4 `GET /api/transactions`

**Query parameters:**

| Param | Type | Notes |
|---|---|---|
| `accountId` | UUID | Optional — omit = all user accounts |
| `from` | ISO 8601 datetime | Optional — `createdAt >= from` |
| `to` | ISO 8601 datetime | Optional — `createdAt <= to` |
| `category` | String (multi) | Optional — `?category=GROCERIES&category=DINING` |
| `type` | String | Optional — DEPOSIT / WITHDRAWAL / TRANSFER |
| `minAmount` | BigDecimal | Optional — `ABS(amount) >= minAmount` |
| `maxAmount` | BigDecimal | Optional — `ABS(amount) <= maxAmount` |
| `status` | String | Optional — PENDING / DEBITED / COMPLETED / FAILED |
| `page` | int | Default `0` |
| `size` | int | Default `10`, max `100` |
| `sort` | String | Default `createdAt,desc`; also `amount,asc` / `amount,desc` |

**Implementation:** `TransactionSpecification` builds `Specification<Transaction>` from non-null params. Amount sort uses `CriteriaBuilder.abs()`. Returns `Page<TransactionResponse>` (Spring `Page<T>` envelope).

---

### 5.5 `GET /api/transactions/summary/monthly`

**Query parameters:**

| Param | Type | Notes |
|---|---|---|
| `year` | int | Optional, default = current year |
| `month` | int | Optional, default = current month (1–12) |

Validation: must not exceed 12 months in the past. Returns `422` for out-of-range.

**Response body:** `MonthlySummaryResponse`
```json
{
  "year": 2026,
  "month": 4,
  "totalSpent": "-1200.0000",
  "totalIncome": "3000.0000",
  "netFlow": "1800.0000",
  "byCategory": [
    { "category": "GROCERIES",     "total": "-250.0000", "transactionCount": 12 },
    { "category": "ENTERTAINMENT", "total": "-80.0000",  "transactionCount": 3  },
    { "category": "INCOME",        "total": "3000.0000", "transactionCount": 2  }
  ]
}
```

- `totalSpent` = sum of all negative amounts
- `totalIncome` = sum of all positive amounts
- `netFlow` = algebraic sum
- Scope: all user's accounts, COMPLETED transactions only

Query: JPQL `GROUP BY t.category` aggregation with `@Query` returning list of `CategorySummary` projections.

---

### 5.6 `PUT /api/transactions/{id}/category`

**Request body:** `UpdateCategoryRequest`
```
category   String  Required — must be a valid TransactionCategory enum value
```

**Happy path (200 OK):**
1. Load transaction; verify `userId == X-User-Id` → 403 if mismatch
2. Verify `status == COMPLETED` → 422 if not
3. Update `category`
4. Write new `txn.categorized` event to `txn_outbox` (AI Advisor needs updated category)
5. Return `200` with updated `TransactionResponse`

**Error cases:**

| Scenario | Status |
|---|---|
| Transaction not found | `404 Not Found` |
| Transaction belongs to different user | `403 Forbidden` |
| Transaction not COMPLETED | `422 Unprocessable Entity` |
| Invalid category value | `400 Bad Request` |

---

## 6. Feign Client — `AccountServiceClient`

Package: `com.wisewallet.transaction.client`
`@FeignClient(name = "account-service", url = "${account.service.url}", configuration = AccountServiceFeignConfig.class)`

### 6.1 Interface Methods

| Method | HTTP | Path | Request body | Returns |
|---|---|---|---|---|
| `getAccount(UUID id)` | GET | `/internal/accounts/{id}` | — | `InternalAccountResponse` |
| `debit(UUID id, DebitCreditRequest)` | POST | `/internal/accounts/{id}/debit` | `{amount}` | `InternalAccountResponse` |
| `credit(UUID id, DebitCreditRequest)` | POST | `/internal/accounts/{id}/credit` | `{amount}` | `InternalAccountResponse` |
| `reserve(UUID id, ReserveRequest)` | POST | `/internal/accounts/{id}/reserve` | `{amount, transactionId}` | `ReserveResponse` |
| `commit(UUID id, UUID reservationId)` | POST | `/internal/accounts/{id}/commit` | `{reservationId}` | `InternalAccountResponse` |
| `release(UUID id, UUID reservationId)` | POST | `/internal/accounts/{id}/release` | `{reservationId}` | `void` |

### 6.2 `AccountServiceFeignConfig`

- `connectTimeout`: 2000ms, `readTimeout`: 5000ms
- `RequestInterceptor`: adds `X-Internal-Key: ${wisewallet.internal.api-key}` to every request
- **No Feign-level retry** — retry logic is explicit in saga code. Feign retry on 5xx would double-apply balance changes.
- Resilience4j `@CircuitBreaker(name = "accountService")` on wrapper service — falls back to `AccountServiceUnavailableException` → 503

### 6.3 Circuit Breaker Config (`application.yml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      accountService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
```

---

## 7. Idempotency Handling

### 7.1 `checkOrInsert(key, userId)` — `@Transactional(propagation = REQUIRES_NEW)`

1. Try INSERT new `IdempotencyKey` (key, userId, expiresAt = now+48h; `responseStatus` and `responseBody` NULL = in-flight)
2. On `DataIntegrityViolationException` (unique constraint violated):
   - Load existing record
   - If `responseBody != null` → return `CachedResponse(status, body)` — replay immediately
   - If `responseBody == null` → throw `DuplicateIdempotencyKeyException` → 409
3. On successful INSERT → return `ProceedSignal`

### 7.2 `complete(key, userId, httpStatus, responseBody)`

Runs in the **same transaction** as the `Transaction` record creation. If the main transaction rolls back, idempotency key is not marked complete, allowing a clean retry.

### 7.3 Cleanup Job

`IdempotencyCleanupJob` — `@Scheduled(cron = "0 0 * * * *")`:
- `DELETE FROM idempotency_keys WHERE expires_at < now()`

---

## 8. Category Rule Engine

### 8.1 `CategoryRuleEngine`

```
categorize(String mccCode, String description) → TransactionCategory:
  1. If mccCode != null → MccCategoryMapping.lookup(mccCode) → return if present
  2. KeywordCategoryMapping.categorize(description) → return if present  (V1: always empty)
  3. Default: return OTHER
```

`TransactionType.TRANSFER` always → `TRANSFER` category. Rule engine bypassed entirely for transfer legs.

### 8.2 `MccCategoryMapping` (sample mappings)

| MCC codes | Category |
|---|---|
| 5411, 5412, 5422 | `GROCERIES` |
| 4900, 4911, 4931 | `UTILITIES` |
| 7832, 7922, 7929, 7941 | `ENTERTAINMENT` |
| 5812, 5813, 5814 | `DINING` |
| 4111, 4121, 4131, 7523 | `TRANSPORT` |
| 5912, 8011, 8021, 8049 | `HEALTHCARE` |
| 5300–5399 range | `SHOPPING` |
| 4411, 4511, 7011 | `TRAVEL` |
| 6012 | `INCOME` |

### 8.3 `KeywordCategoryMapping`

V1 stub — `categorize(description) → Optional.empty()`. Class exists and is injected. Implement keyword matching here in future without touching `CategoryRuleEngine`.

---

## 9. Kafka Configuration

### 9.1 Producer Config

| Property | Value |
|---|---|
| `key.serializer` | `StringSerializer` |
| `value.serializer` | `JsonSerializer` |
| `acks` | `all` |
| `retries` | `3` |
| `enable.idempotence` | `true` |
| `compression.type` | `snappy` |
| `linger.ms` | `5` |

### 9.2 Topics Produced

| Topic | Partitions (local / prod) | Key | Published when | Outbox? |
|---|---|---|---|---|
| `txn.created` | 3 / 6 | `accountId` | Transaction reaches COMPLETED | Yes |
| `txn.categorized` | 3 / 6 | `accountId` | After rule engine runs; also on user override | Yes |

### 9.3 Event Schemas

**`TransactionCreatedEvent`** (record):
```
eventId          UUID
transactionId    UUID
transferId       UUID       nullable
userId           UUID
accountId        UUID
amount           BigDecimal  signed
type             String      DEPOSIT | WITHDRAWAL | TRANSFER
status           String      COMPLETED
createdAt        Instant
```

**`TransactionCategorizedEvent`** (record):
```
eventId          UUID
transactionId    UUID
userId           UUID
accountId        UUID
amount           BigDecimal  signed
category         String
categorizedAt    Instant
```

### 9.4 Outbox Poller (`OutboxPublisherService`)

- `@Scheduled(fixedDelay = 2000)` — every 2 seconds
- `SELECT ... WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 50`
- On Kafka success → `status = 'SENT'`
- On failure → `retry_count++`; if `retry_count >= 5` → `status = 'FAILED'` (log ERROR)
- Concurrent ECS instances: use `SELECT ... FOR UPDATE SKIP LOCKED`

---

## 10. Test Plan

### 10.1 Unit Tests

| Class | What to test | Key mocks |
|---|---|---|
| `DepositService` | happy path, inactive account 422, idempotency replay | `AccountServiceClient`, `IdempotencyService`, `TransactionRepository`, `OutboxEventRepository` |
| `WithdrawalService` | happy path, insufficient funds 422, optimistic lock 409 retry | same |
| `TransferService` | happy path; same-account block; insufficient balance; credit-fails rollback; commit-fails CRITICAL log | `AccountServiceClient`, `IdempotencyService`, `TransactionRepository`, `OutboxEventRepository` |
| `CategoryService` | MCC matched; MCC unmatched → OTHER; TRANSFER bypasses engine; override republishes outbox event | `CategoryRuleEngine`, `TransactionRepository`, `OutboxEventRepository` |
| `CategoryRuleEngine` | MCC lookup hits; MCC miss → OTHER; TRANSFER type → TRANSFER | None |
| `IdempotencyService` | fresh key proceeds; in-flight key 409; completed key replays | `IdempotencyKeyRepository` |
| `TransactionQueryService` | filter combinations; pagination; sort by amount; monthly aggregation boundary | `TransactionRepository` |
| `OutboxPublisherService` | pending rows published; SENT marked; retry_count incremented; FAILED after max retries | `OutboxEventRepository`, `KafkaTemplate` |
| `MonthlySummaryResponse` builder | correct totalSpent/totalIncome/netFlow math | None |

### 10.2 `@WebMvcTest` Slice Tests

| Test class | Endpoints | Key assertions |
|---|---|---|
| `TransactionControllerTest` | deposit, withdraw, transfer | Auth required; validation errors 400; 403 on accountId not in claims; idempotency header required |
| `TransactionListControllerTest` | `GET /api/transactions` | Filter params bind; pagination envelope shape; sort params |
| `MonthlySummaryControllerTest` | `GET /api/transactions/summary/monthly` | Default year/month; out-of-range 422; response shape |
| `CategoryControllerTest` | `PUT /api/transactions/{id}/category` | Invalid category 400; non-COMPLETED 422; wrong user 403 |

### 10.3 `@DataJpaTest` Slice Tests

Use Testcontainers PostgreSQL.

| Test class | What to verify |
|---|---|
| `TransactionRepositoryTest` | Each filter in isolation + combined; pagination; sort by ABS(amount); monthly aggregation projection correctness |
| `IdempotencyKeyRepositoryTest` | Unique constraint prevents duplicate (key, user_id); expired cleanup query |

### 10.4 Integration Tests (Testcontainers + WireMock)

WireMock simulates Account Service responses.

| Test class | Containers | Scenario |
|---|---|---|
| `DepositIntegrationTest` | PostgreSQL, WireMock | Full deposit flow; outbox event written; idempotency replay on second call |
| `WithdrawalIntegrationTest` | PostgreSQL, WireMock | Happy path; insufficient funds WireMock 422 → 422 propagated |
| `TransferSagaHappyPathIT` | PostgreSQL, WireMock | All Feign calls succeed; both legs COMPLETED; outbox has 4 events |
| `TransferSagaRollbackIT` | PostgreSQL, WireMock | WireMock simulates credit 422; release called; both legs FAILED |
| `TransferSagaCommitFailIT` | PostgreSQL, WireMock | WireMock simulates commit timeout; ERROR logged; idempotency key NOT completed |
| `IdempotencyIntegrationTest` | PostgreSQL | Two identical requests; second returns cached response; only one transaction record |
| `OutboxPollerIntegrationTest` | PostgreSQL, Kafka | Outbox poller picks up events; messages appear on correct topics with correct keys |
| `MonthlyAggregationIT` | PostgreSQL | Seed 12 months of data; verify boundary filtering and category math |
| `FilterIntegrationTest` | PostgreSQL | Seed varied transactions; verify each filter parameter excludes correct records |

### 10.5 Coverage Targets

- Line: **80%** minimum (JaCoCo gate)
- Branch: **70%** minimum
- `TransferService`: **90%+** (all saga branches must be covered)
- `IdempotencyService`: **90%+**

---

## 11. Configuration Keys

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    hikari:
      maximum-pool-size: 15
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: transaction
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
    default-schema: transaction

wisewallet:
  internal:
    api-key: ${INTERNAL_API_KEY}
  transaction:
    outbox:
      poll-fixed-delay-ms: 2000
      batch-size: 50
      max-retries: 5
    idempotency:
      ttl-hours: 48
      cleanup-cron: "0 0 * * * *"
    kafka:
      topics:
        txn-created: txn.created
        txn-categorized: txn.categorized
    pagination:
      default-page-size: 10
    saga:
      feign-retry-attempts: 3
      feign-retry-backoff-ms: 100

resilience4j:
  circuitbreaker:
    instances:
      accountService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3

account:
  service:
    url: ${ACCOUNT_SERVICE_URL:http://localhost:8081}
```

---

## 12. Sequencing of Work (Transaction Service Build Order)

| Step | Task | Depends on |
|---|---|---|
| 1 | Apply Account Service amendments (migration `006`, reservation table, 5 new internal endpoints, `ReservationCleanupService`, remove `TransactionCreatedConsumer`) | Account Service plan complete |
| 2 | Transaction Service Liquibase changesets 001–003 + skeleton entities + repositories | Foundation skeleton |
| 3 | `@DataJpaTest` for `TransactionRepository` filter specs + `IdempotencyKeyRepository` | Step 2 |
| 4 | `IdempotencyService` + unit tests | Step 2 |
| 5 | `AccountServiceClient` + `AccountServiceFeignConfig` + WireMock stubs for all 6 internal endpoints | Step 2 |
| 6 | `CategoryRuleEngine` + `MccCategoryMapping` + unit tests (pure logic, no Spring context) | None |
| 7 | `DepositService` + `WithdrawalService` + `@WebMvcTest` + unit tests | Steps 4, 5, 6 |
| 8 | Deposit + Withdrawal integration tests (Testcontainers + WireMock) | Step 7 |
| 9 | `TransferService` (full saga, all branches) + unit tests | Steps 4, 5, 6 |
| 10 | Transfer integration tests (happy path, rollback, commit-fail) | Step 9 |
| 11 | `TransactionQueryService` + filter/pagination/sort + monthly aggregation | Step 2 |
| 12 | `CategoryService` + user override endpoint + republish outbox | Steps 6, 11 |
| 13 | `OutboxPublisherService` + Kafka integration tests | Step 2 |
| 14 | Monthly aggregation integration test (boundary conditions) | Step 11 |
| 15 | JaCoCo gate + Dockerfile + verify traces in Jaeger locally | Steps 1–14 |

---

## 13. Open Items for Downstream Plans

| Item | Affects |
|---|---|
| `txn.created` payload schema (§9.3) — consumers must match this exact shape | Notification Service, AI Advisor |
| `txn.categorized` payload schema (§9.3) | Notification Service, AI Advisor |
| `account.balance-low` published by Account Service after debit/commit (synchronously) | Notification Service |
| `ACCOUNT_SERVICE_URL` env var needed in Transaction Service ECS task definition | Infra/Terraform plan |
| `INTERNAL_API_KEY` shared between Account Service and Transaction Service | Infra/Terraform plan |
| Transfer commit-fail edge case → metric `transfer.commit.failure.count` + alert | Observability plan |
