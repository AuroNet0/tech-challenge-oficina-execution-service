package com.oficina.execution.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class MongoConnectivityIntegrationTest {

	private static final String COLLECTION = "bootstrap_smoke_tests";

	@Container
	static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

	@DynamicPropertySource
	static void configureMongo(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("oficina_execution_mongo_test"));
	}

	@Autowired
	private MongoTemplate mongoTemplate;

	@AfterEach
	void cleanUp() {
		mongoTemplate.dropCollection(COLLECTION);
	}

	@Test
	void performsBasicMongoOperationsThroughSpringData() {
		Document document = new Document("_id", "bootstrap-1")
				.append("service", "execution")
				.append("status", "ok");

		mongoTemplate.insert(document, COLLECTION);

		Document saved = mongoTemplate.findById("bootstrap-1", Document.class, COLLECTION);

		assertThat(saved).isNotNull();
		assertThat(saved.getString("service")).isEqualTo("execution");
		assertThat(saved.getString("status")).isEqualTo("ok");
		assertThat(mongoTemplate.collectionExists(COLLECTION)).isTrue();
	}
}
