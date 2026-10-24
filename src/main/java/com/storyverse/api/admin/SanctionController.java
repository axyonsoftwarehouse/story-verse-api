package com.storyverse.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

/** Sanções no painel. As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin")
public class SanctionController {

	public record ApplyRequest(String kind, String reason, Instant expiresAt) {
	}

	public record RevokeRequest(String reason) {
	}

	private final AuthService auth;
	private final SanctionService sanctions;

	public SanctionController(AuthService auth, SanctionService sanctions) {
		this.auth = auth;
		this.sanctions = sanctions;
	}

	@PostMapping("/users/{id}/sanctions")
	public Sanction apply(
			@PathVariable UUID id,
			@RequestBody ApplyRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		return sanctions.apply(actor, id, request.kind(), request.reason(), request.expiresAt());
	}

	@PostMapping("/sanctions/{id}/revoke")
	public Sanction revoke(
			@PathVariable UUID id,
			@RequestBody(required = false) RevokeRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		return sanctions.revoke(actor, id, request == null ? null : request.reason());
	}

	@GetMapping("/users/{id}/sanctions")
	public List<Sanction> history(
			@PathVariable UUID id,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		return sanctions.history(actor, id, limit);
	}

	@GetMapping("/sanctions")
	public SanctionPage list(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String kind,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		return sanctions.list(actor, status, kind, q, cursor, limit);
	}
}
