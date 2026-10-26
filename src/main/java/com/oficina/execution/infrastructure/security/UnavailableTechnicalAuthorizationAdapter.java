package com.oficina.execution.infrastructure.security;

import com.oficina.execution.application.exception.AuthorizationUnavailableException;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("!mtls")
public class UnavailableTechnicalAuthorizationAdapter implements TechnicalAuthorizationPort {
    @Override
    public Decision decide(Request request) {
        throw new AuthorizationUnavailableException();
    }
}
