package com.oficina.execution.infrastructure.web.exception;

import com.mongodb.MongoException;
import com.oficina.execution.application.exception.ResourceNotFoundException;
import com.oficina.execution.domain.exception.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.ServletRequestBindingException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail notFound(ResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domainConflict(DomainException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({DuplicateKeyException.class, OptimisticLockingFailureException.class})
    public ProblemDetail persistenceConflict(Exception exception) {
        return problem(HttpStatus.CONFLICT, "Conflito de persistencia. Consulte o recurso antes de tentar novamente.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class,
            HandlerMethodValidationException.class})
    public ProblemDetail invalidInput(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Entrada invalida. Verifique campos obrigatorios, UUIDs, enums e valores decimais.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo && (mongo.getCode() == 112
                    || mongo.hasErrorLabel("TransientTransactionError"))) {
                return persistenceConflict(exception);
            }
        }
        if (exception instanceof ErrorResponse response) {
            return problem(HttpStatus.valueOf(response.getStatusCode().value()), "Requisicao nao suportada.");
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno ao processar a solicitacao.");
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
