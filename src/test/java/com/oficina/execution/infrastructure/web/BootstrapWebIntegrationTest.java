package com.oficina.execution.infrastructure.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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
class BootstrapWebIntegrationTest {

	@Container
	static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

	@DynamicPropertySource
	static void configureMongo(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("oficina_execution_web_test"));
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpointsArePublicAndReadyWhenMongoIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void swaggerEndpointsArePublic() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Tech Challenge Oficina - Execution Service"));

		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/swagger-ui/swagger-ui/index.html"));
	}

	@Test
	void nonPublicEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/internal/bootstrap"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedRequestsCanReachInternalBootstrapStatus() throws Exception {
		mockMvc.perform(get("/internal/bootstrap").with(user("api")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.service").value("execution-service"))
				.andExpect(jsonPath("$.status").value("BOOTSTRAPPED"));
	}
}
