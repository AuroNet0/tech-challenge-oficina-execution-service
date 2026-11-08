package com.oficina.execution.infrastructure.web;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/bootstrap")
class BootstrapStatusController {

	@GetMapping
	ResponseEntity<BootstrapStatusResponse> status() {
		return ResponseEntity.ok(new BootstrapStatusResponse("execution-service", "BOOTSTRAPPED", Instant.now()));
	}

	record BootstrapStatusResponse(String service, String status, Instant checkedAt) {
	}
}
