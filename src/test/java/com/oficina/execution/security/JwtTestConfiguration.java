package com.oficina.execution.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@TestConfiguration
public class JwtTestConfiguration {
    @Bean(destroyMethod = "close")
    JwksFixture jwksFixture() throws Exception { return new JwksFixture(); }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(JwksFixture fixture) { return fixture.decoder(); }
}
