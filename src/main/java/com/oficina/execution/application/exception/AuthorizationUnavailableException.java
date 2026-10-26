package com.oficina.execution.application.exception;

public class AuthorizationUnavailableException extends RuntimeException {
    public AuthorizationUnavailableException() {
        super("Autorizacao operacional indisponivel.");
    }
}
