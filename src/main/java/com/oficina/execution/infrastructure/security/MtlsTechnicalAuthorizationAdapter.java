package com.oficina.execution.infrastructure.security;

import com.oficina.execution.application.exception.AuthorizationUnavailableException;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Component
@Profile("mtls")
public final class MtlsTechnicalAuthorizationAdapter implements TechnicalAuthorizationPort {
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public MtlsTechnicalAuthorizationAdapter(@Value("${oficina.internal.os-uri}") URI endpoint) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !"/internal/technical-authorization".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Trusted HTTPS authorization endpoint required");
        }
        this.endpoint = endpoint;
    }

    @Override
    public Decision decide(Request request) {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken user) || !user.isAuthenticated()
                    || !user.getToken().getSubject().equals(request.usuarioId().toString())) {
                throw new AuthorizationUnavailableException();
            }
            var response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + user.getToken().getTokenValue())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 1024
                    || !"no-store".equals(response.headers().firstValue("Cache-Control").orElse(""))) {
                throw new AuthorizationUnavailableException();
            }
            return json.readValue(response.body(), Decision.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuthorizationUnavailableException();
        } catch (Exception exception) { throw new AuthorizationUnavailableException(); }
    }
}
