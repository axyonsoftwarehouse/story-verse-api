package com.storyverse.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Contas no painel do admin. As permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

	private final AdminUserService service;

	public AdminUserController(AdminUserService service) {
		this.service = service;
	}

	@GetMapping
	public List<AdminUserSummary> search(
			@RequestParam(required = false) String q,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.search(AuthTokens.bearer(authorization), q);
	}

	@GetMapping("/{id}")
	public AdminUserDetail detail(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.detail(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/{id}/sessions/revoke")
	public Map<String, Object> revokeSessions(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.revokeSessions(AuthTokens.bearer(authorization), id);
	}

	@PutMapping("/{id}/roles/{role}")
	public Map<String, Object> grantRole(
			@PathVariable UUID id,
			@PathVariable String role,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.grantRole(AuthTokens.bearer(authorization), id, role);
	}

	@DeleteMapping("/{id}/roles/{role}")
	public Map<String, Object> revokeRole(
			@PathVariable UUID id,
			@PathVariable String role,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.revokeRole(AuthTokens.bearer(authorization), id, role);
	}
}
