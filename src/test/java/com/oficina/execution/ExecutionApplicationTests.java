package com.oficina.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ExecutionApplicationTests {

	@Container
	static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

	@DynamicPropertySource
	static void configureMongo(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("oficina_execution_test"));
	}

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
		assertThat(mongoTemplate).isNotNull();
		assertThat(mockMvc).isNotNull();
		assertThat(mongoTemplate.getDb().getName()).isEqualTo("oficina_execution_test");
	}
}
