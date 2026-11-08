package com.oficina.execution.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import com.oficina.execution.security.JwksFixture;
import com.oficina.execution.security.JwtTestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(JwtTestConfiguration.class)
class BootstrapWebIntegrationTest {

	@Container
	static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

	@DynamicPropertySource
	static void configureMongo(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("oficina_execution_web_test"));
	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired JwksFixture jwks;

	@Test
	void healthEndpointsArePublicAndReadyWhenMongoIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist())
				.andExpect(jsonPath("$.status").value("UP"));

		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void swaggerEndpointsAreDenied() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void nonPublicEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/internal/bootstrap"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedUsersCannotReachInternalEndpointsOrDocumentation() throws Exception {
		for (String path : java.util.List.of("/internal/bootstrap", "/v3/api-docs", "/swagger-ui/index.html",
				"/actuator/env", "/actuator/metrics", "/unknown")) {
			mockMvc.perform(get(path).header("Authorization", "Bearer " + jwks.token("GERENTE")))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void missingTrustedAdapterBlocksAssignedOperationsInProductionWiring() throws Exception {
		mockMvc.perform(get("/api/v1/execucoes/historico").param("osId", java.util.UUID.randomUUID().toString())
				.header("Authorization", "Bearer " + jwks.token("MECANICO")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value("Autorizacao operacional indisponivel."));
	}
}
