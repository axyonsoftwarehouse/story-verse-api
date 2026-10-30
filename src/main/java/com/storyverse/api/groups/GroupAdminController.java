package com.storyverse.api.groups;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Denúncias de mensagens dos grupos, para a equipe. */
@RestController
@RequestMapping("/api/v1/admin/groups")
public class GroupAdminController {

	private final GroupService service;

	public GroupAdminController(GroupService service) {
		this.service = service;
	}

	@GetMapping("/reports")
	public List<Map<String, Object>> reports(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.adminReports(AuthTokens.bearer(authorization), status, limit);
	}

	@PatchMapping("/reports/{id}")
	public Map<String, Object> resolve(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.resolveReport(AuthTokens.bearer(authorization), id, body);
	}
}
