package com.story_magic.api.system.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

	@GetMapping
	public HealthResponse health() {
		return new HealthResponse("ok", Instant.now());
	}

	public record HealthResponse(String status, Instant timestamp) {
	}
}
