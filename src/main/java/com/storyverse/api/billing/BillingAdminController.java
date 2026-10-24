package com.storyverse.api.billing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Financeiro somente leitura (superadmin). */
@RestController
@RequestMapping("/api/v1/admin/billing")
public class BillingAdminController {

	private final BillingAdminService service;

	public BillingAdminController(BillingAdminService service) {
		this.service = service;
	}

	@GetMapping("/summary")
	public Map<String, Object> summary(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.summary(AuthTokens.bearer(authorization), from, to);
	}

	@GetMapping("/subscriptions")
	public List<Map<String, Object>> subscriptions(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String q,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.subscriptions(AuthTokens.bearer(authorization), status, q);
	}

	@GetMapping("/subscriptions/{id}")
	public Map<String, Object> subscription(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.subscription(AuthTokens.bearer(authorization), id);
	}
}
