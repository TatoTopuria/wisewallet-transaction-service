package com.wisewallet.transaction.domain.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }
}
