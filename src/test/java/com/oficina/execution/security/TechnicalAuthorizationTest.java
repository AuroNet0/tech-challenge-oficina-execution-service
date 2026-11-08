package com.oficina.execution.security;

import com.oficina.execution.application.exception.AuthorizationUnavailableException;
import com.oficina.execution.application.port.in.DiagnosticUseCase;
import com.oficina.execution.application.port.in.RepairUseCase;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort.*;
import com.oficina.execution.domain.model.Diagnostic;
import com.oficina.execution.domain.model.Repair;
import com.oficina.execution.infrastructure.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TechnicalAuthorizationTest {
    DiagnosticUseCase diagnostics = mock(DiagnosticUseCase.class);
    RepairUseCase repairs = mock(RepairUseCase.class);
    TechnicalAuthorizationPort port = mock(TechnicalAuthorizationPort.class);
    TechnicalAuthorization policy = new TechnicalAuthorization(diagnostics, repairs, port);
    UUID osId = UUID.randomUUID();

    JwtAuthenticationToken actor(String role) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "RS256")
                .subject(JwksFixture.USER.toString()).build(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @BeforeEach
    void allowExactRequest() {
        doAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            return new Decision(request.usuarioId(), request.osId(), request.capability(), request.correlationId(), true);
        }).when(port).decide(any());
    }

    @Test
    void resolvesPersistedOsAndRequestsSeparateCapabilities() {
        var diagnostic = Diagnostic.create(osId, UUID.randomUUID()).start();
        var repair = Repair.create(osId, diagnostic.sagaId(), diagnostic.id());
        when(diagnostics.findById(diagnostic.id())).thenReturn(diagnostic);
        when(repairs.findById(repair.id())).thenReturn(repair);
        assertThat(policy.diagnostic(actor("MECANICO"), diagnostic.id(), "DIAGNOSTIC_WRITE")).isTrue();
        assertThat(policy.repair(actor("MECANICO"), repair.id(), "REPAIR_COMPLETE")).isTrue();
        verify(port).decide(argThatRequest(Capability.DIAGNOSTIC_WRITE));
        verify(port).decide(argThatRequest(Capability.REPAIR_COMPLETE));
    }

    private Request argThatRequest(Capability capability) {
        return org.mockito.ArgumentMatchers.argThat(request -> request.osId().equals(osId)
                && request.usuarioId().equals(JwksFixture.USER) && request.capability() == capability
                && request.correlationId() != null);
    }

    @Test
    void rolesNeverInheritMechanicPermissions() {
        for (String role : List.of("CLIENTE", "ATENDENTE", "GERENTE", "")) {
            assertThat(policy.os(actor(role), osId, "HISTORY_READ")).isFalse();
            assertThat(policy.diagnostic(actor(role), UUID.randomUUID(), "DIAGNOSTIC_WRITE")).isFalse();
            assertThat(policy.repair(actor(role), UUID.randomUUID(), "REPAIR_COMPLETE")).isFalse();
        }
        assertThat(policy.os(null, osId, "HISTORY_READ")).isFalse();
        verifyNoInteractions(port, diagnostics, repairs);
    }

    @Test
    void deniesAbsentRevokedAndMismatchedDecisionsWithoutReusingRead() {
        for (int variant = 0; variant < 6; variant++) {
            int selected = variant;
            doAnswer(invocation -> {
                Request request = invocation.getArgument(0);
                return switch (selected) {
                    case 0 -> new Decision(request.usuarioId(), osId, request.capability(), request.correlationId(), false);
                    case 1 -> new Decision(request.usuarioId(), UUID.randomUUID(), request.capability(), request.correlationId(), true);
                    case 2 -> new Decision(request.usuarioId(), osId, Capability.DIAGNOSTIC_READ, request.correlationId(), true);
                    case 3 -> new Decision(UUID.randomUUID(), osId, request.capability(), request.correlationId(), true);
                    case 4 -> new Decision(request.usuarioId(), osId, request.capability(), UUID.randomUUID(), true);
                    default -> null;
                };
            }).when(port).decide(any());
            assertThat(policy.os(actor("MECANICO"), osId, "DIAGNOSTIC_WRITE")).isFalse();
        }
    }

    @Test
    void outageAndMissingTrustedAdapterFailClosed() {
        doThrow(new IllegalStateException("private connection details")).when(port).decide(any());
        assertThatThrownBy(() -> policy.os(actor("MECANICO"), osId, "HISTORY_READ"))
                .isInstanceOf(AuthorizationUnavailableException.class).hasMessage("Autorizacao operacional indisponivel.");
        assertThatThrownBy(() -> new UnavailableTechnicalAuthorizationAdapter().decide(null))
                .isInstanceOf(AuthorizationUnavailableException.class);
    }

    @Test
    void httpAdapterRequiresHttpsAndServiceCredential() {
        assertThatThrownBy(() -> new TechnicalAuthorizationHttpAdapter(URI.create("http://os.test/decision"), () -> "token"))
                .isInstanceOf(IllegalArgumentException.class);
        var adapter = new TechnicalAuthorizationHttpAdapter(URI.create("https://os.test/decision"), () -> "");
        assertThatThrownBy(() -> adapter.decide(new Request(JwksFixture.USER, osId, Capability.HISTORY_READ, UUID.randomUUID())))
                .isInstanceOf(AuthorizationUnavailableException.class);
    }
}
