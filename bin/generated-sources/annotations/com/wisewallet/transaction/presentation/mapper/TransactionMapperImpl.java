package com.wisewallet.transaction.presentation.mapper;

import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.domain.model.TransactionCategory;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.model.TransactionType;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-05-11T17:24:09+0400",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.0.v20260407-0427, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class TransactionMapperImpl implements TransactionMapper {

    @Override
    public TransactionResponse toResponse(Transaction transaction) {
        if ( transaction == null ) {
            return null;
        }

        UUID id = null;
        UUID accountId = null;
        UUID transferId = null;
        BigDecimal amount = null;
        String currency = null;
        TransactionType type = null;
        TransactionStatus status = null;
        TransactionCategory category = null;
        Instant createdAt = null;

        id = transaction.getId();
        accountId = transaction.getAccountId();
        transferId = transaction.getTransferId();
        amount = transaction.getAmount();
        currency = transaction.getCurrency();
        type = transaction.getType();
        status = transaction.getStatus();
        category = transaction.getCategory();
        createdAt = transaction.getCreatedAt();

        TransactionResponse transactionResponse = new TransactionResponse( id, accountId, transferId, amount, currency, type, status, category, createdAt );

        return transactionResponse;
    }
}
