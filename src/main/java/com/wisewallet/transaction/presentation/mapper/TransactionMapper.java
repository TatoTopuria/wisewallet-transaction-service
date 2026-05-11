package com.wisewallet.transaction.presentation.mapper;

import com.wisewallet.transaction.domain.model.Transaction;
import com.wisewallet.transaction.presentation.dto.response.TransactionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface TransactionMapper {

    TransactionResponse toResponse(Transaction transaction);
}
