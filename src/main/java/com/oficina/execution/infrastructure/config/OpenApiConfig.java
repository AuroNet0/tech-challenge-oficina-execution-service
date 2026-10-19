package com.oficina.execution.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI executionServiceOpenAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("Tech Challenge Oficina - Execution Service")
						.version("0.0.1")
						.description("Microsservico responsavel pelo diagnostico e execucao futura dos reparos."));
	}
}
