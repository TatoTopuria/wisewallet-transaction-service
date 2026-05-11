package com.wisewallet.transaction.application.command;

import com.wisewallet.transaction.domain.event.TransactionCategorizedDomainEvent;
import com.wisewallet.transaction.domain.exception.BusinessRuleException;
import com.wisewallet.transaction.domain.exception.TransactionNotFoundException;
import com.wisewallet.transaction.domain.model.TransactionStatus;
import com.wisewallet.transaction.domain.repository.TransactionRepositoryPort;
import com.wisewallet.transaction.presentation.dto.request.UpdateCategoryRequest;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import com.wisewallet.transaction.presentation.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryCommandService {

    private final TransactionRepositoryPort transactionRepository;
    private final TransactionMapper transactionMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public TransactionResponse updateCategory(UUID transactionId, UUID userId, UpdateCategoryRequest request) {
        var txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction not found: " + transactionId));

        if (!txn.getUserId().equals(userId)) {
            throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Transaction does not belong to the requesting user");
        }

        if (txn.getStatus() != TransactionStatus.COMPLETED) {
            throw new BusinessRuleException(
                    "Category can only be updated on COMPLETED transactions");
        }

        txn.setCategory(request.category());
        transactionRepository.save(txn);

        eventPublisher.publishEvent(new TransactionCategorizedDomainEvent(txn));

        return transactionMapper.toResponse(txn);
    }
}
