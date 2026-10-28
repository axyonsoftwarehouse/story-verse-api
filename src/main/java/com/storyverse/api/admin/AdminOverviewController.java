package com.storyverse.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Visão geral do painel. As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/overview")
public class AdminOverviewController {

	private final AdminOverviewService service;

	public AdminOverviewController(AdminOverviewService service) {
		this.service = service;
	}

	@GetMapping
	public AdminOverview overview(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.overview(AuthTokens.bearer(authorization));
	}
}
