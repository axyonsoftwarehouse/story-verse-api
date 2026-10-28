package com.storyverse.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Tráfego do site (Umami). As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/traffic")
public class AdminTrafficController {

	private final AdminTrafficService service;

	public AdminTrafficController(AdminTrafficService service) {
		this.service = service;
	}

	@GetMapping
	public AdminTraffic traffic(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.traffic(AuthTokens.bearer(authorization), from, to);
	}
}
