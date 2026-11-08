package com.oficina.execution.domain.model;

import com.oficina.execution.domain.exception.DomainException;

final class DomainValidation {
    private DomainValidation() {
    }

    static <T> T required(T value, String field) {
        if (value == null) throw new DomainException(field + " is required");
        return value;
    }

    static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new DomainException(field + " is required");
        return value.strip();
    }
}
