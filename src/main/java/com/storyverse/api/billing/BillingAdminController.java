package com.storyverse.api.billing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Financeiro (superadmin): leitura e acesso grátis manual. */
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

	@GetMapping("/series")
	public List<BillingSeriesPoint> series(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(defaultValue = "day") String group,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.series(AuthTokens.bearer(authorization), from, to, group);
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

	public record GrantRequest(UUID userId, String reason, String expiresAt) {}

	@GetMapping("/grants")
	public List<Map<String, Object>> grants(
			@RequestParam UUID userId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.grants(AuthTokens.bearer(authorization), userId);
	}

	@PostMapping("/grants")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> grant(@RequestBody GrantRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		if (request == null) throw new com.storyverse.api.auth.AuthException(400, "Informe a conta.");
		return service.grant(AuthTokens.bearer(authorization), request.userId(), request.reason(), request.expiresAt());
	}

	@DeleteMapping("/grants/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.revokeGrant(AuthTokens.bearer(authorization), id);
	}
}
