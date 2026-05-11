package com.wisewallet.transaction.domain.exception;

public class AccountInactiveException extends BusinessRuleException {

    public AccountInactiveException(String message) {
        super(message);
    }
}
