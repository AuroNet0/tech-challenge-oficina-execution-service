package com.oficina.execution.infrastructure.security;

import com.nimbusds.jwt.SignedJWT;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestOperations;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuthJwtDecoder implements JwtDecoder {
    private static final Set<String> ROLES = Set.of("CLIENTE", "ATENDENTE", "MECANICO", "GERENTE");
    private final NimbusJwtDecoder delegate;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public AuthJwtDecoder(String issuer, String audience, String jwksUri, Clock clock) {
        this(issuer, audience, jwksUri, clock, restOperations());
    }

    public AuthJwtDecoder(String issuer, String audience, String jwksUri, Clock clock, RestOperations restOperations) {
        this(issuer, audience, jwksUri, clock, restOperations, Ticker.systemTicker());
    }

    public AuthJwtDecoder(String issuer, String audience, String jwksUri, Clock clock, RestOperations restOperations, Ticker ticker) {
        URI endpoint = URI.create(jwksUri);
        if (issuer.isBlank() || audience.isBlank() || !"https".equals(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Issuer, audience and HTTPS JWKS URI are required");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
        var cache = new CaffeineCache("auth-jwks", Caffeine.newBuilder().maximumSize(1)
                .expireAfterWrite(Duration.ofSeconds(60)).ticker(ticker).build());
        delegate = NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256)
                .cache(cache).restOperations(restOperations).jwtProcessorCustomizer(processor -> {
                    var selector = processor.getJWSKeySelector();
                    processor.setJWSKeySelector((header, context) -> {
                        var keys = selector.selectJWSKeys(header, context);
                        return keys.size() == 1 ? keys : List.of();
                    });
                }).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        delegate.setJwtValidator(timestamps);
    }

    private static RestOperations restOperations() {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return new RestTemplate(factory);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        try {
            if (token == null || token.length() > 8192) { throw invalid(); }
            var parsed = SignedJWT.parse(token);
            var header = parsed.getHeader().toJSONObject();
            if (!"RS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))
                    || !(header.get("kid") instanceof String keyId) || keyId.isBlank()
                    || !Set.of("alg", "typ", "kid").containsAll(header.keySet())) { throw invalid(); }
            Map<String, Object> claims = parsed.getPayload().toJSONObject();
            if (!issuer.equals(claims.get("iss")) || !canonicalUuid(claims.get("sub"))
                    || !canonicalUuid(claims.get("jti"))) { throw invalid(); }
            if (!(claims.get("aud") instanceof List<?> audiences) || audiences.isEmpty()
                    || audiences.stream().anyMatch(value -> !(value instanceof String))
                    || !audiences.contains(audience)) { throw invalid(); }
            if (!(claims.get("roles") instanceof List<?> roles)
                    || roles.stream().anyMatch(value -> !(value instanceof String) || !ROLES.contains(value))
                    || roles.stream().distinct().count() != roles.size()) { throw invalid(); }
            long issued = numericDate(claims.get("iat"));
            long notBefore = numericDate(claims.get("nbf"));
            long expires = numericDate(claims.get("exp"));
            long now = clock.instant().getEpochSecond();
            if (issued < 0 || issued > now || notBefore != issued || expires <= now || expires <= issued
                    || expires - issued > 900) { throw invalid(); }
            return delegate.decode(token);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static long numericDate(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) { throw invalid(); }
        return ((Number) value).longValue();
    }

    private static boolean canonicalUuid(Object value) {
        if (!(value instanceof String text)) { return false; }
        try { return UUID.fromString(text).toString().equals(text); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static BadJwtException invalid() { return new BadJwtException("Invalid access token"); }
}
