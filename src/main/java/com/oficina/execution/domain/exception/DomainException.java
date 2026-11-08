package com.oficina.execution.domain.exception;

public class DomainException extends IllegalArgumentException {
    public DomainException(String message) {
        super(message);
    }
}
