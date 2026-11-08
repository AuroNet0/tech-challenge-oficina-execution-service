package com.oficina.execution.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.oficina.execution.infrastructure.security.AuthJwtDecoder;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class JwksFixture implements AutoCloseable {
    public static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final String ISSUER = "https://auth.example.test";
    private final HttpServer server;
    public final RSAKey key;
    public final AtomicInteger requests = new AtomicInteger();
    public volatile String keys;
    public volatile int status = 200;

    public JwksFixture() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        keys = new JWKSet(key.toPublicJWK()).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            byte[] body = keys.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60, must-revalidate");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    public AuthJwtDecoder decoder() {
        return decoder(Ticker.systemTicker());
    }

    public AuthJwtDecoder decoder(Ticker ticker) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
                return super.createRequest(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"), method);
            }
        };
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return new AuthJwtDecoder(ISSUER, "oficina", ISSUER + "/jwks", Clock.systemUTC(), new RestTemplate(factory), ticker);
    }

    public Map<String, Object> claims(String... roles) {
        long now = Instant.now().getEpochSecond();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of("oficina"));
        claims.put("sub", USER.toString());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + 900);
        claims.put("roles", List.of(roles));
        return claims;
    }

    public String token(String... roles) {
        return sign(claims(roles), key, JWSAlgorithm.RS256, key.getKeyID());
    }

    public String sign(Map<String, Object> claims, RSAKey signingKey, JWSAlgorithm algorithm, String kid) {
        try {
            var header = new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(kid).build();
            var signed = new JWSObject(header, new Payload(claims));
            signed.sign(new RSASSASigner(signingKey));
            return signed.serialize();
        } catch (JOSEException exception) { throw new IllegalStateException(exception); }
    }

    @Override
    public void close() { server.stop(0); }
}
