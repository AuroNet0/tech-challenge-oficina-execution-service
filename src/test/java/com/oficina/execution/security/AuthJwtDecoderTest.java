package com.oficina.execution.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.oficina.execution.infrastructure.security.AuthJwtDecoder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class AuthJwtDecoderTest {
    static JwksFixture fixture;
    @BeforeAll static void start() throws Exception { fixture = new JwksFixture(); }
    @AfterAll static void stop() { fixture.close(); }

    @Test
    void validatesRealSignatureAndCachesPublicKeys() {
        var decoder = fixture.decoder();
        int before = fixture.requests.get();
        assertThat(decoder.decode(fixture.token("MECANICO")).getSubject()).isEqualTo(JwksFixture.USER.toString());
        assertThat(decoder.decode(fixture.token()).getClaimAsStringList("roles")).isEmpty();
        assertThat(fixture.requests.get() - before).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "sub", "jti", "iat", "nbf", "exp", "roles"})
    void rejectsMissingClaims(String claim) {
        var claims = fixture.claims("MECANICO");
        claims.remove(claim);
        reject(claims);
    }

    @Test
    void cacheExpiresAfterSixtySecondsWithoutExtendingOnReads() {
        var elapsed = new AtomicLong();
        var decoder = fixture.decoder(elapsed::get);
        int before = fixture.requests.get();
        String token = fixture.token("MECANICO");
        decoder.decode(token);
        elapsed.set(TimeUnit.SECONDS.toNanos(59));
        decoder.decode(token);
        assertThat(fixture.requests.get() - before).isEqualTo(1);
        elapsed.set(TimeUnit.SECONDS.toNanos(60));
        decoder.decode(token);
        assertThat(fixture.requests.get() - before).isEqualTo(2);
    }

    @Test
    void expiredCacheRefreshesKeysAndRejectsRemovedKey() throws Exception {
        var elapsed = new AtomicLong();
        var decoder = fixture.decoder(elapsed::get);
        String oldToken = fixture.token("MECANICO");
        decoder.decode(oldToken);
        var next = new RSAKeyGenerator(2048).keyID("replacement").generate();
        String previous = fixture.keys;
        try {
            fixture.keys = new JWKSet(next.toPublicJWK()).toString();
            elapsed.set(TimeUnit.SECONDS.toNanos(59));
            assertThat(decoder.decode(oldToken)).isNotNull();
            elapsed.set(TimeUnit.SECONDS.toNanos(60));
            assertThatThrownBy(() -> decoder.decode(oldToken)).isInstanceOf(JwtException.class);
            assertThat(decoder.decode(fixture.sign(fixture.claims("MECANICO"), next,
                    JWSAlgorithm.RS256, next.getKeyID()))).isNotNull();
        } finally { fixture.keys = previous; }
    }

    @Test
    void unavailableJwksNeverUsesExpiredCacheAndRecoversAfterRefresh() {
        var elapsed = new AtomicLong();
        var decoder = fixture.decoder(elapsed::get);
        String token = fixture.token("MECANICO");
        decoder.decode(token);
        try {
            fixture.status = 503;
            elapsed.set(TimeUnit.SECONDS.toNanos(59));
            assertThat(decoder.decode(token)).isNotNull();
            elapsed.set(TimeUnit.SECONDS.toNanos(60));
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        } finally { fixture.status = 200; }
        assertThat(decoder.decode(token)).isNotNull();
    }

    @Test
    void rejectsInvalidClaimsAndTimeWindows() {
        long now = Instant.now().getEpochSecond();
        for (Map.Entry<String, Object> invalid : Map.<String, Object>ofEntries(
                Map.entry("iss", "https://other.test"), Map.entry("aud", List.of("other")),
                Map.entry("sub", "1-1-1-1-1"), Map.entry("jti", "invalid"), Map.entry("roles", "MECANICO"),
                Map.entry("iat", now + 100), Map.entry("nbf", now + 100), Map.entry("exp", now - 1)).entrySet()) {
            var claims = fixture.claims("MECANICO"); claims.put(invalid.getKey(), invalid.getValue()); reject(claims);
        }
        for (Object roles : List.of(List.of("ADMIN"), List.of("MECANICO", "MECANICO"), List.of(42))) {
            var claims = fixture.claims(); claims.put("roles", roles); reject(claims);
        }
        for (Object value : List.of("123", 1.5, -1L, now + 901)) {
            var claims = fixture.claims(); claims.put("iat", value); reject(claims);
        }
        var excessive = fixture.claims(); excessive.put("exp", now + 1000); reject(excessive);
        var audience = fixture.claims(); audience.put("aud", "oficina"); reject(audience);
        audience.put("aud", List.of("oficina", 42)); reject(audience);
    }

    @Test
    void rejectsUnsignedAlgorithmsKeysAndMalformedTokens() throws Exception {
        var other = new RSAKeyGenerator(2048).keyID("other").generate();
        var decoder = fixture.decoder();
        var hmac = new com.nimbusds.jose.JWSObject(new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(com.nimbusds.jose.JOSEObjectType.JWT).keyID(fixture.key.getKeyID()).build(),
                new com.nimbusds.jose.Payload(fixture.claims("MECANICO")));
        hmac.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[32]));
        var unsigned = new com.nimbusds.jwt.PlainJWT(com.nimbusds.jwt.JWTClaimsSet.parse(fixture.claims("MECANICO")));
        for (String token : List.of("invalid", "eyJhbGciOiJub25lIn0.e30.",
                hmac.serialize(), unsigned.serialize(),
                "eyJhbGciOiJIUzI1NiIsImtpZCI6InRlc3Qta2V5In0.e30.aGVsbG8",
                fixture.sign(fixture.claims(), fixture.key, JWSAlgorithm.RS384, fixture.key.getKeyID()),
                fixture.sign(fixture.claims(), other, JWSAlgorithm.RS256, fixture.key.getKeyID()),
                fixture.sign(fixture.claims(), fixture.key, JWSAlgorithm.RS256, null),
                fixture.sign(fixture.claims(), fixture.key, JWSAlgorithm.RS256, "unknown"), "a".repeat(8193))) {
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
        assertThatThrownBy(() -> decoder.decode(null)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAmbiguousKidAndUntrustedHeaderKeys() throws Exception {
        var second = new RSAKeyGenerator(2048).keyID(fixture.key.getKeyID()).generate();
        String previous = fixture.keys;
        try {
            fixture.keys = new JWKSet(List.of(fixture.key.toPublicJWK(), second.toPublicJWK())).toString();
            assertThatThrownBy(() -> fixture.decoder().decode(fixture.token("MECANICO"))).isInstanceOf(JwtException.class);
        } finally { fixture.keys = previous; }
        var header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(com.nimbusds.jose.JOSEObjectType.JWT).keyID(fixture.key.getKeyID()).jwk(second.toPublicJWK()).build();
        var signed = new com.nimbusds.jose.JWSObject(header, new com.nimbusds.jose.Payload(fixture.claims("MECANICO")));
        signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(fixture.key));
        assertThatThrownBy(() -> fixture.decoder().decode(signed.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void refreshesForNewKidAndFailsClosedWhenJwksUnavailable() throws Exception {
        var decoder = fixture.decoder();
        decoder.decode(fixture.token("MECANICO"));
        var next = new RSAKeyGenerator(2048).keyID("rotated").generate();
        String previous = fixture.keys;
        try {
            fixture.keys = new JWKSet(List.of(fixture.key.toPublicJWK(), next.toPublicJWK())).toString();
            assertThat(decoder.decode(fixture.sign(fixture.claims(), next, JWSAlgorithm.RS256, "rotated"))).isNotNull();
            fixture.status = 503;
            assertThat(decoder.decode(fixture.token())).isNotNull();
            assertThatThrownBy(() -> fixture.decoder().decode(fixture.token())).isInstanceOf(JwtException.class);
        } finally { fixture.status = 200; fixture.keys = previous; }
    }

    @Test
    void rejectsMissingConfigurationAndInsecureJwks() {
        for (String[] config : List.of(new String[]{"", "oficina", "https://auth.test/jwks"},
                new String[]{"issuer", "", "https://auth.test/jwks"}, new String[]{"issuer", "oficina", "http://auth.test/jwks"})) {
            assertThatThrownBy(() -> new AuthJwtDecoder(config[0], config[1], config[2], Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void reject(Map<String, Object> claims) {
        assertThatThrownBy(() -> fixture.decoder().decode(fixture.sign(claims, fixture.key,
                JWSAlgorithm.RS256, fixture.key.getKeyID()))).isInstanceOf(JwtException.class);
    }
}
