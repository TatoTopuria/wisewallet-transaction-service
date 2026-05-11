package com.wisewallet.transaction.presentation.controller;

import com.wisewallet.transaction.application.command.CategoryCommandService;
import com.wisewallet.transaction.presentation.dto.request.UpdateCategoryRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Categories", description = "Transaction category management endpoints")
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class CategoryController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final CategoryCommandService categoryCommandService;

    @Operation(summary = "Update transaction category", description = "Manually assigns or overrides the category for a transaction owned by the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "404", description = "Transaction not found"),
            @ApiResponse(responseCode = "403", description = "User does not own this transaction")
    })
    @PutMapping("/{id}/category")
    public ResponseEntity<TransactionResponse> updateCategory(
            @Parameter(description = "Transaction UUID", required = true)
            @PathVariable UUID id,
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @Valid @RequestBody UpdateCategoryRequest request) {

        return ResponseEntity.ok(categoryCommandService.updateCategory(id, userId, request));
    }
}
