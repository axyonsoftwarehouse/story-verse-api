package com.storyverse.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Saúde do sistema (Sentry + backup). As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/health")
public class AdminHealthController {

	private final AdminHealthService service;

	public AdminHealthController(AdminHealthService service) {
		this.service = service;
	}

	@GetMapping
	public AdminHealth health(
			@RequestParam(required = false) String period,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.health(AuthTokens.bearer(authorization), period);
	}
}
