package com.oficina.execution.infrastructure.security;

import com.oficina.execution.application.exception.AuthorizationUnavailableException;
import com.oficina.execution.application.port.out.TechnicalAuthorizationPort;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

public final class TechnicalAuthorizationHttpAdapter implements TechnicalAuthorizationPort {
    private final RestClient client;
    private final URI endpoint;
    private final ServiceCredential credential;

    public TechnicalAuthorizationHttpAdapter(URI endpoint, ServiceCredential credential) {
        this(endpoint, credential, client());
    }

    TechnicalAuthorizationHttpAdapter(URI endpoint, ServiceCredential credential, RestClient client) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Trusted HTTPS authorization endpoint required");
        }
        this.client = client;
        this.endpoint = endpoint;
        this.credential = java.util.Objects.requireNonNull(credential);
    }

    private static RestClient client() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Decision decide(Request request) {
        try {
            String serviceToken = credential.accessToken();
            if (serviceToken == null || serviceToken.isBlank()) { throw new AuthorizationUnavailableException(); }
            return client.post().uri(endpoint).headers(headers -> {
                headers.setBearerAuth(serviceToken);
                headers.set("X-Correlation-Id", request.correlationId().toString());
            }).body(request).retrieve().body(Decision.class);
        } catch (RuntimeException exception) {
            throw new AuthorizationUnavailableException();
        }
    }

    public interface ServiceCredential {
        String accessToken();
    }
}
