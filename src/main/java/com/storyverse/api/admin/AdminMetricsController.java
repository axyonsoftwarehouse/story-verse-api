package com.storyverse.api.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Métricas de produto no painel. As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/metrics")
public class AdminMetricsController {

	private final AdminMetricsService service;

	public AdminMetricsController(AdminMetricsService service) {
		this.service = service;
	}

	@GetMapping("/overview")
	public AdminMetrics overview(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.overview(AuthTokens.bearer(authorization), from, to);
	}

	@GetMapping("/reading")
	public List<AdminMetricsReadingPoint> reading(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(defaultValue = "day") String group,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.readingSeries(AuthTokens.bearer(authorization), from, to, group);
	}

	@GetMapping("/retention")
	public List<AdminMetrics.Retention> retention(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.retention(AuthTokens.bearer(authorization), from, to);
	}

	@GetMapping("/top-books")
	public List<AdminTopBook> topBooks(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.topBooks(AuthTokens.bearer(authorization), from, to, limit);
	}
}
