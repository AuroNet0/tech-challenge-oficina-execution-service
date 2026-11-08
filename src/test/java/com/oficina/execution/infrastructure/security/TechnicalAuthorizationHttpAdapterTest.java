package com.oficina.execution.infrastructure.security;

import com.oficina.execution.application.exception.AuthorizationUnavailableException;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TechnicalAuthorizationHttpAdapterTest {
    private final URI endpoint = URI.create("https://os.example.test/internal/technical-authorization");
    private final Request request = new Request(UUID.randomUUID(), UUID.randomUUID(), Capability.REPAIR_WRITE, UUID.randomUUID());

    @Test
    void usesSeparateServiceCredentialAndMinimalBoundContract() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(endpoint)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-only-token"))
                .andExpect(header("X-Correlation-Id", request.correlationId().toString()))
                .andExpect(jsonPath("$.usuarioId").value(request.usuarioId().toString()))
                .andExpect(jsonPath("$.osId").value(request.osId().toString()))
                .andExpect(jsonPath("$.capability").value("REPAIR_WRITE"))
                .andRespond(withSuccess("""
                        {"usuarioId":"%s","osId":"%s","capability":"REPAIR_WRITE","correlationId":"%s","allowed":true}
                        """.formatted(request.usuarioId(), request.osId(), request.correlationId()), MediaType.APPLICATION_JSON));
        var adapter = new TechnicalAuthorizationHttpAdapter(endpoint, () -> "service-only-token", builder.build());
        assertThat(adapter.decide(request)).isEqualTo(new Decision(request.usuarioId(), request.osId(), request.capability(), request.correlationId(), true));
        server.verify();
    }

    @Test
    void upstreamErrorsNeverGrantAccessOrExposeDetails() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private upstream details"));
        var adapter = new TechnicalAuthorizationHttpAdapter(endpoint, () -> "service-only-token", builder.build());
        assertThatThrownBy(() -> adapter.decide(request)).isInstanceOf(AuthorizationUnavailableException.class)
                .hasMessage("Autorizacao operacional indisponivel.");
        server.verify();
    }

    @Test
    void absentCredentialNeverPerformsNetworkRequest() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new TechnicalAuthorizationHttpAdapter(endpoint, () -> null, builder.build());
        assertThatThrownBy(() -> adapter.decide(request)).isInstanceOf(AuthorizationUnavailableException.class);
        server.verify();
    }
}
