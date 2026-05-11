package com.wisewallet.transaction.infrastructure.client;

import com.wisewallet.transaction.infrastructure.client.config.AccountServiceFeignConfig;
import com.wisewallet.transaction.infrastructure.client.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@FeignClient(
        name = "account-service",
        url = "${account.service.url}",
        configuration = AccountServiceFeignConfig.class
)
public interface AccountServiceFeignClient {

    @GetMapping("/internal/accounts/{id}")
    InternalAccountResponse getAccount(@PathVariable("id") UUID id);

    @PostMapping("/internal/accounts/{id}/balances/{currency}/debit")
    void debit(@PathVariable("id") UUID id,
               @PathVariable("currency") String currency,
               @RequestBody DebitCreditRequest request);

    @PostMapping("/internal/accounts/{id}/balances/{currency}/credit")
    void credit(@PathVariable("id") UUID id,
                @PathVariable("currency") String currency,
                @RequestBody DebitCreditRequest request);

    @PostMapping("/internal/accounts/{id}/balances/{currency}/reserve")
    ReserveResponse reserve(@PathVariable("id") UUID id,
                            @PathVariable("currency") String currency,
                            @RequestBody ReserveRequest request);

    @PostMapping("/internal/accounts/{id}/balances/{currency}/commit")
    void commit(@PathVariable("id") UUID id,
                @PathVariable("currency") String currency,
                @RequestBody CommitReleaseRequest request);

    @PostMapping("/internal/accounts/{id}/balances/{currency}/release")
    void release(@PathVariable("id") UUID id,
                 @PathVariable("currency") String currency,
                 @RequestBody CommitReleaseRequest request);
}
