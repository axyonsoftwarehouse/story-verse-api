package com.storyverse.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
	private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
	public HealthController(org.springframework.jdbc.core.simple.JdbcClient jdbc) { this.jdbc = jdbc; }

	@GetMapping("/ready")
	public HealthResponse ready() {
		jdbc.sql("select 1 from public.reading_state limit 0").query().listOfRows();
		return new HealthResponse("ready", Instant.now());
	}

	@GetMapping
	public HealthResponse health() {
		return new HealthResponse("ok", Instant.now());
	}

	public record HealthResponse(String status, Instant timestamp) {
	}
}
