package com.wisewallet.transaction.presentation.controller;

import com.wisewallet.transaction.application.command.DepositCommandService;
import com.wisewallet.transaction.application.command.TransferCommandService;
import com.wisewallet.transaction.application.command.WithdrawalCommandService;
import com.wisewallet.transaction.application.query.TransactionQueryService;
import com.wisewallet.transaction.domain.exception.SameAccountTransferException;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.presentation.dto.request.DepositRequest;
import com.wisewallet.transaction.presentation.dto.request.TransferRequest;
import com.wisewallet.transaction.presentation.dto.request.WithdrawalRequest;
import com.wisewallet.transaction.presentation.dto.response.MonthlySummaryResponse;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.dto.response.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "Transactions", description = "Financial transaction management endpoints")
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ACCOUNT_IDS_HEADER = "X-Account-Ids";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final DepositCommandService depositCommandService;
    private final WithdrawalCommandService withdrawalCommandService;
    private final TransferCommandService transferCommandService;
    private final TransactionQueryService transactionQueryService;

    @Operation(summary = "Create a deposit", description = "Credits funds to an account. Idempotent per key.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Deposit created"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key"),
            @ApiResponse(responseCode = "503", description = "Account service unavailable")
    })
    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "Comma-separated list of account IDs owned by the user", required = true)
            @RequestHeader(ACCOUNT_IDS_HEADER) String accountIdsHeader,
            @Parameter(description = "Client-supplied idempotency key (max 255 chars)", required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        validateAccountOwnership(request.accountId(), accountIdsHeader);
        TransactionResponse response = depositCommandService.deposit(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Create a withdrawal", description = "Debits funds from an account. Idempotent per key.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Withdrawal created"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key"),
            @ApiResponse(responseCode = "422", description = "Insufficient funds or business rule violation"),
            @ApiResponse(responseCode = "503", description = "Account service unavailable")
    })
    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "Comma-separated list of account IDs owned by the user", required = true)
            @RequestHeader(ACCOUNT_IDS_HEADER) String accountIdsHeader,
            @Parameter(description = "Client-supplied idempotency key (max 255 chars)", required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {

        validateAccountOwnership(request.accountId(), accountIdsHeader);
        TransactionResponse response = withdrawalCommandService.withdraw(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Create a transfer", description = "Moves funds between two accounts belonging to the same user using a 6-phase saga. Idempotent per key.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer completed"),
            @ApiResponse(responseCode = "400", description = "Invalid request or same source/destination"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key"),
            @ApiResponse(responseCode = "422", description = "Insufficient funds or business rule violation"),
            @ApiResponse(responseCode = "503", description = "Account service unavailable")
    })
    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "Comma-separated list of account IDs owned by the user", required = true)
            @RequestHeader(ACCOUNT_IDS_HEADER) String accountIdsHeader,
            @Parameter(description = "Client-supplied idempotency key (max 255 chars)", required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new SameAccountTransferException("Source and destination accounts must be different");
        }
        validateAccountOwnership(request.sourceAccountId(), accountIdsHeader);
        validateAccountOwnership(request.destinationAccountId(), accountIdsHeader);

        TransferResponse response = transferCommandService.transfer(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List transactions", description = "Returns a filtered, paginated list of transactions for the authenticated user.")
    @ApiResponse(responseCode = "200", description = "Page of transactions")
    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> listTransactions(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "Comma-separated list of account IDs owned by the user", required = true)
            @RequestHeader(ACCOUNT_IDS_HEADER) String accountIdsHeader,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        size = Math.min(size, 100);

        if (accountId != null) {
            validateAccountOwnership(accountId, accountIdsHeader);
        }

        List<TransactionCategory> categories = category != null
                ? category.stream().map(TransactionCategory::valueOf).collect(Collectors.toList())
                : null;

        Pageable pageable = buildPageable(page, size, sort);

        Page<TransactionResponse> result = transactionQueryService.listTransactions(
                userId, accountId, from, to, categories,
                type != null ? TransactionType.valueOf(type) : null,
                minAmount, maxAmount,
                status != null ? TransactionStatus.valueOf(status) : null,
                pageable);

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Monthly summary", description = "Aggregates income, expenses, and net balance by category for a given month.")
    @ApiResponse(responseCode = "200", description = "Monthly summary")
    @GetMapping("/summary/monthly")
    public ResponseEntity<MonthlySummaryResponse> monthlySummary(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        int resolvedYear = year != null ? year : now.getYear();
        int resolvedMonth = month != null ? month : now.getMonthValue();

        return ResponseEntity.ok(transactionQueryService.monthlySummary(userId, resolvedYear, resolvedMonth));
    }

    private void validateAccountOwnership(UUID accountId, String accountIdsHeader) {
        List<UUID> allowedIds;
        try {
            allowedIds = Arrays.stream(accountIdsHeader.split(","))
                    .map(String::trim)
                    .map(UUID::fromString)
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "X-Account-Ids header contains an invalid UUID value");
        }

        if (!allowedIds.contains(accountId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Account " + accountId + " does not belong to the requesting user");
        }
    }

    private Pageable buildPageable(int page, int size, String sortParam) {
        String[] parts = sortParam.split(",");
        String field = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;

        if ("amount".equals(field)) {
            return PageRequest.of(page, size, Sort.by(direction, "createdAt"));
        }
        return PageRequest.of(page, size, Sort.by(direction, field));
    }
}
