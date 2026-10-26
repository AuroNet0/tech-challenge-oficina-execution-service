package com.oficina.execution.infrastructure.security;

import com.oficina.execution.application.exception.AuthorizationUnavailableException;
import com.oficina.execution.application.port.in.DiagnosticUseCase;
import com.oficina.execution.application.port.in.RepairUseCase;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort.Capability;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("technicalAuthorization")
public class TechnicalAuthorization {
    private final DiagnosticUseCase diagnostics;
    private final RepairUseCase repairs;
    private final TechnicalAuthorizationPort authorization;

    public TechnicalAuthorization(DiagnosticUseCase diagnostics, RepairUseCase repairs,
            TechnicalAuthorizationPort authorization) {
        this.diagnostics = diagnostics;
        this.repairs = repairs;
        this.authorization = authorization;
    }

    public boolean diagnostic(Authentication actor, UUID id, String capability) {
        return mechanic(actor) && os(actor, diagnostics.findById(id).osId(), capability);
    }

    public boolean repair(Authentication actor, UUID id, String capability) {
        return mechanic(actor) && os(actor, repairs.findById(id).osId(), capability);
    }

    public boolean os(Authentication actor, UUID osId, String capability) {
        if (!mechanic(actor)) { return false; }
        var request = new TechnicalAuthorizationPort.Request(UUID.fromString(actor.getName()), osId,
                Capability.valueOf(capability), UUID.randomUUID());
        try {
            var decision = authorization.decide(request);
            return decision != null && decision.allowed() && request.usuarioId().equals(decision.usuarioId())
                    && request.osId().equals(decision.osId()) && request.capability() == decision.capability()
                    && request.correlationId().equals(decision.correlationId());
        } catch (RuntimeException exception) {
            throw new AuthorizationUnavailableException();
        }
    }

    private boolean mechanic(Authentication actor) {
        return actor instanceof JwtAuthenticationToken && actor.isAuthenticated()
                && actor.getAuthorities().stream().anyMatch(authority -> "ROLE_MECANICO".equals(authority.getAuthority()));
    }
}
