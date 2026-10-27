package com.storyverse.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Usuários ativos (DAU/WAU/MAU). As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/metrics")
public class AdminActiveController {

	private final AdminActiveService service;

	public AdminActiveController(AdminActiveService service) {
		this.service = service;
	}

	@GetMapping("/active")
	public AdminActive active(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.active(AuthTokens.bearer(authorization), from, to);
	}
}
