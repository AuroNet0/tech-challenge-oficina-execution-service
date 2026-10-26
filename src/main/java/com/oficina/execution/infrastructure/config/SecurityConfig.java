package com.oficina.execution.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import com.oficina.execution.infrastructure.security.AuthJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
	@Bean
	JwtDecoder jwtDecoder(@Value("${execution.security.jwt.issuer}") String issuer,
			@Value("${execution.security.jwt.audience}") String audience,
			@Value("${execution.security.jwt.jwks-uri}") String jwksUri) {
		return new AuthJwtDecoder(issuer, audience, jwksUri, Clock.systemUTC());
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("roles");
		authorities.setAuthorityPrefix("ROLE_");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.GET,
								"/actuator/health",
								"/actuator/health/liveness",
								"/actuator/health/readiness")
						.permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/diagnosticos", "/api/v1/diagnosticos/*",
								"/api/v1/reparos", "/api/v1/reparos/*", "/api/v1/execucoes/historico").hasRole("MECANICO")
						.requestMatchers(HttpMethod.POST, "/api/v1/diagnosticos/*/itens",
								"/api/v1/diagnosticos/*/conclusao", "/api/v1/reparos/*/conclusao").hasRole("MECANICO")
						.requestMatchers(HttpMethod.PATCH, "/api/v1/diagnosticos/*/observacoes",
								"/api/v1/reparos/*/observacoes").hasRole("MECANICO")
						.anyRequest()
						.denyAll())
				.oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
				.build();
	}
}
