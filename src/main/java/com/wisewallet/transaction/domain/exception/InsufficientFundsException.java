package com.wisewallet.transaction.domain.exception;

public class InsufficientFundsException extends BusinessRuleException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
