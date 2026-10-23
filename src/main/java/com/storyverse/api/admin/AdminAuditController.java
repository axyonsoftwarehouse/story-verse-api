package com.storyverse.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Consulta da auditoria (só leitura, só superadmin). */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

	private final AdminAuditService service;

	public AdminAuditController(AdminAuditService service) {
		this.service = service;
	}

	@GetMapping
	public AdminAuditPage audit(
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String actorId,
			@RequestParam(required = false) String targetType,
			@RequestParam(required = false) String targetId,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String before,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.audit(AuthTokens.bearer(authorization),
				new AdminAuditService.Filter(action, actorId, targetType, targetId, from, to, before));
	}
}
