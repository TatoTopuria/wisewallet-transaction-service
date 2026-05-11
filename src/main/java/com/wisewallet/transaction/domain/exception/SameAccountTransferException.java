package com.wisewallet.transaction.domain.exception;

public class SameAccountTransferException extends BusinessRuleException {

    public SameAccountTransferException(String message) {
        super(message);
    }
}
